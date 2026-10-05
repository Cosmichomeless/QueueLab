package com.queuelab.core.outbox;

import java.sql.ResultSet;
import java.sql.SQLException;
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

    private final JdbcClient jdbc;

    public OutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(OutboxEvent event) {
        jdbc.sql("""
                INSERT INTO outbox_events (id, job_id, event_type, payload, created_at, published_at, attempts)
                VALUES (:id, :jobId, :eventType, CAST(:payload AS jsonb), :createdAt, :publishedAt, :attempts)
                """)
                .param("id", event.id())
                .param("jobId", event.jobId())
                .param("eventType", event.eventType())
                .param("payload", event.payload())
                .param("createdAt", event.createdAt().atOffset(ZoneOffset.UTC))
                .param("publishedAt", event.publishedAt() == null ? null : event.publishedAt().atOffset(ZoneOffset.UTC))
                .param("attempts", event.attempts())
                .update();
    }

    public List<OutboxEvent> findByJobId(UUID jobId) {
        return jdbc.sql("SELECT id, job_id, event_type, payload::text AS payload, created_at, published_at, attempts "
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
                rs.getInt("attempts"));
    }
}
