package com.queuelab.core.job;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

/** Acceso a la tabla {@code jobs}. */
public class JobRepository {

    private final JdbcClient jdbc;

    public JobRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Job job) {
        jdbc.sql("""
                INSERT INTO jobs (id, type, status, created_at, updated_at, started_at, finished_at)
                VALUES (:id, :type, :status, :createdAt, :updatedAt, :startedAt, :finishedAt)
                """)
                .param("id", job.id())
                .param("type", job.type())
                .param("status", job.status().name())
                .param("createdAt", utc(job.createdAt()))
                .param("updatedAt", utc(job.updatedAt()))
                .param("startedAt", utc(job.startedAt()))
                .param("finishedAt", utc(job.finishedAt()))
                .update();
    }

    public Optional<Job> findById(UUID id) {
        return jdbc.sql("SELECT * FROM jobs WHERE id = :id")
                .param("id", id)
                .query(JobRepository::map)
                .optional();
    }

    /**
     * Página de trabajos, del más reciente al más antiguo. El orden es total
     * ({@code created_at DESC, id DESC}), así que paginar con el cursor del último elemento
     * no repite ni se salta trabajos aunque se creen otros mientras tanto.
     *
     * @param status filtro por estado, o {@code null} para todos
     * @param after  cursor del último trabajo ya visto, o {@code null} para empezar
     */
    public List<Job> findPage(JobStatus status, JobCursor after, int limit) {
        var sql = new StringBuilder("SELECT * FROM jobs WHERE true");
        if (status != null) {
            sql.append(" AND status = :status");
        }
        if (after != null) {
            sql.append(" AND (created_at, id) < (:afterCreatedAt, :afterId)");
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT :limit");

        var statement = jdbc.sql(sql.toString()).param("limit", limit);
        if (status != null) {
            statement = statement.param("status", status.name());
        }
        if (after != null) {
            statement = statement.param("afterCreatedAt", utc(after.createdAt())).param("afterId", after.id());
        }
        return statement.query(JobRepository::map).list();
    }

    /**
     * Guarda el nuevo estado solo si en base de datos sigue en {@code expectedStatus}
     * (control optimista: evita que dos procesos se pisen).
     *
     * @return {@code true} si se actualizó; {@code false} si el estado ya había cambiado
     */
    public boolean update(Job job, JobStatus expectedStatus) {
        return jdbc.sql("""
                UPDATE jobs
                   SET status = :status, updated_at = :updatedAt,
                       started_at = :startedAt, finished_at = :finishedAt,
                       result = :result, error = :error
                 WHERE id = :id AND status = :expectedStatus
                """)
                .param("id", job.id())
                .param("status", job.status().name())
                .param("updatedAt", utc(job.updatedAt()))
                .param("startedAt", utc(job.startedAt()))
                .param("finishedAt", utc(job.finishedAt()))
                .param("result", job.result())
                .param("error", job.error())
                .param("expectedStatus", expectedStatus.name())
                .update() == 1;
    }

    private static Job map(ResultSet rs, int rowNum) throws SQLException {
        return new Job(
                rs.getObject("id", UUID.class),
                rs.getString("type"),
                JobStatus.valueOf(rs.getString("status")),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
                instant(rs.getObject("started_at", OffsetDateTime.class)),
                instant(rs.getObject("finished_at", OffsetDateTime.class)),
                rs.getString("result"),
                rs.getString("error"));
    }

    private static java.time.Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(java.time.Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }
}
