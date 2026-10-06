package com.queuelab.core.outbox;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Acceso a la tabla {@code outbox_events}. {@link #insert} debe llamarse dentro de la misma
 * transacción que guarda el trabajo para que ambos se confirmen o se deshagan juntos.
 */
public class OutboxRepository {

    /** Longitud de la columna {@code last_error}. */
    private static final int LAST_ERROR_MAX = 500;

    private final JdbcClient jdbc;

    public OutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(OutboxEvent event) {
        insert(event, null);
    }

    /**
     * Inserta el evento para que no se publique hasta {@code availableAt} ({@code null}: de inmediato).
     * Es lo que usa el worker para programar un reintento con espera, dentro de la misma transacción
     * que marca el trabajo como {@code RETRYING}.
     */
    public void insert(OutboxEvent event, Instant availableAt) {
        jdbc.sql("""
                INSERT INTO outbox_events (id, job_id, event_type, payload, created_at, published_at, attempts,
                                           available_at, correlation_id)
                VALUES (:id, :jobId, :eventType, CAST(:payload AS jsonb), :createdAt, :publishedAt, :attempts,
                        :availableAt, :correlationId)
                """)
                .param("correlationId", event.correlationId())
                .param("availableAt", availableAt == null ? null : availableAt.atOffset(ZoneOffset.UTC))
                .param("id", event.id())
                .param("jobId", event.jobId())
                .param("eventType", event.eventType())
                .param("payload", event.payload())
                .param("createdAt", event.createdAt().atOffset(ZoneOffset.UTC))
                .param("publishedAt", event.publishedAt() == null ? null : event.publishedAt().atOffset(ZoneOffset.UTC))
                .param("attempts", event.attempts())
                .update();
    }

    /**
     * Eventos sin publicar y ya disponibles ({@code available_at} vacío o vencido), del más antiguo al más
     * reciente. Bloquea las filas ({@code SKIP LOCKED}) hasta el
     * fin de la transacción en curso, así dos instancias de la API no publican el mismo evento a la vez.
     * Debe llamarse dentro de una transacción.
     */
    public List<OutboxEvent> findPending(int limit) {
        return jdbc.sql("SELECT id, job_id, event_type, payload::text AS payload, created_at, published_at, attempts, "
                        + "correlation_id "
                        + "FROM outbox_events WHERE published_at IS NULL "
                        + "AND (available_at IS NULL OR available_at <= now()) ORDER BY created_at, id "
                        + "LIMIT :limit FOR UPDATE SKIP LOCKED")
                .param("limit", limit)
                .query(OutboxRepository::map)
                .list();
    }

    /** Eventos sin publicar y ya disponibles, y desde cuándo espera el más antiguo ({@code null} si no hay). */
    public record PendingStats(long count, Instant oldestCreatedAt) {
    }

    /**
     * Cuántos eventos están listos para publicarse y cuánto lleva esperando el más antiguo: si crece, el
     * despachador no da abasto o RabbitMQ no confirma. Se sirve del índice parcial
     * {@code outbox_events_pending_idx}, que solo contiene lo pendiente. Los reintentos programados a futuro
     * ({@code available_at}) no cuentan: aún no tocaba publicarlos.
     */
    public PendingStats pendingStats() {
        return jdbc.sql("SELECT count(*) AS total, min(created_at) AS oldest FROM outbox_events "
                        + "WHERE published_at IS NULL AND (available_at IS NULL OR available_at <= now())")
                .query((rs, rowNum) -> {
                    OffsetDateTime oldest = rs.getObject("oldest", OffsetDateTime.class);
                    return new PendingStats(rs.getLong("total"), oldest == null ? null : oldest.toInstant());
                })
                .single();
    }

    /** Marca el evento como confirmado por el broker. */
    public void markPublished(UUID id, Instant at) {
        jdbc.sql("UPDATE outbox_events SET published_at = :at, last_error = NULL WHERE id = :id")
                .param("at", at.atOffset(ZoneOffset.UTC))
                .param("id", id)
                .update();
    }

    /** Anota un intento fallido: el evento sigue pendiente y se reintentará. */
    public void recordFailure(UUID id, String error) {
        String trimmed = error == null ? null : error.substring(0, Math.min(error.length(), LAST_ERROR_MAX));
        jdbc.sql("UPDATE outbox_events SET attempts = attempts + 1, last_error = :error WHERE id = :id")
                .param("error", trimmed)
                .param("id", id)
                .update();
    }

    public List<OutboxEvent> findByJobId(UUID jobId) {
        return jdbc.sql("SELECT id, job_id, event_type, payload::text AS payload, created_at, published_at, attempts, "
                        + "correlation_id "
                        + "FROM outbox_events WHERE job_id = :jobId ORDER BY created_at, id")
                .param("jobId", jobId)
                .query(OutboxRepository::map)
                .list();
    }

    private static OutboxEvent map(ResultSet rs, int row) throws SQLException {
        OffsetDateTime published = rs.getObject("published_at", OffsetDateTime.class);
        return new OutboxEvent(
                rs.getObject("id", UUID.class),
                rs.getObject("job_id", UUID.class),
                rs.getString("event_type"),
                rs.getString("payload"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                published == null ? null : published.toInstant(),
                rs.getInt("attempts"),
                rs.getString("correlation_id"));
    }
}
