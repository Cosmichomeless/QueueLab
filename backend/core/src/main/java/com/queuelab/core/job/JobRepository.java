package com.queuelab.core.job;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
     * Guarda el nuevo estado solo si en base de datos sigue en {@code expectedStatus}
     * (control optimista: evita que dos procesos se pisen).
     *
     * @return {@code true} si se actualizó; {@code false} si el estado ya había cambiado
     */
    public boolean update(Job job, JobStatus expectedStatus) {
        return jdbc.sql("""
                UPDATE jobs
                   SET status = :status, updated_at = :updatedAt,
                       started_at = :startedAt, finished_at = :finishedAt
                 WHERE id = :id AND status = :expectedStatus
                """)
                .param("id", job.id())
                .param("status", job.status().name())
                .param("updatedAt", utc(job.updatedAt()))
                .param("startedAt", utc(job.startedAt()))
                .param("finishedAt", utc(job.finishedAt()))
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
                instant(rs.getObject("finished_at", OffsetDateTime.class)));
    }

    private static java.time.Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(java.time.Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }
}
