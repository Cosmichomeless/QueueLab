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

    /**
     * Inserta el trabajo con su clave de idempotencia salvo que la clave ya exista. La comprobación es
     * atómica ({@code ON CONFLICT DO NOTHING} sobre el índice único), así que dos peticiones simultáneas
     * con la misma clave nunca crean dos trabajos y la transacción no se aborta.
     *
     * @return {@code true} si se insertó; {@code false} si la clave ya estaba usada
     */
    public boolean insertIfKeyAbsent(Job job, String idempotencyKey, String fingerprint) {
        return jdbc.sql("""
                INSERT INTO jobs (id, type, status, created_at, updated_at, started_at, finished_at,
                                  idempotency_key, request_fingerprint)
                VALUES (:id, :type, :status, :createdAt, :updatedAt, :startedAt, :finishedAt, :key, :fingerprint)
                ON CONFLICT (idempotency_key) WHERE idempotency_key IS NOT NULL DO NOTHING
                """)
                .param("id", job.id())
                .param("type", job.type())
                .param("status", job.status().name())
                .param("createdAt", utc(job.createdAt()))
                .param("updatedAt", utc(job.updatedAt()))
                .param("startedAt", utc(job.startedAt()))
                .param("finishedAt", utc(job.finishedAt()))
                .param("key", idempotencyKey)
                .param("fingerprint", fingerprint)
                .update() == 1;
    }

    public Optional<StoredSubmission> findByIdempotencyKey(String idempotencyKey) {
        return jdbc.sql("SELECT * FROM jobs WHERE idempotency_key = :key")
                .param("key", idempotencyKey)
                .query((rs, rowNum) -> new StoredSubmission(map(rs, rowNum), rs.getString("request_fingerprint")))
                .optional();
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

    /**
     * Reclama el trabajo para ejecutarlo: en <b>una sola sentencia</b> pasa {@code QUEUED} o
     * {@code RETRYING} a {@code RUNNING} y
     * suma un intento. PostgreSQL serializa los {@code UPDATE} sobre la misma fila y el segundo
     * reevalúa {@code status = 'QUEUED'} ya sin éxito, así que solo un worker recibe el trabajo.
     *
     * <p>El reclamo abre el lease: hasta {@code leaseExpiresAt} el trabajo se considera en manos de este
     * worker, que debe renovarlo ({@link #renewLease}) mientras siga ejecutándolo.
     *
     * @return el trabajo ya en {@code RUNNING} con su número de intento, o vacío si no estaba esperando
     *         ejecución (otro worker lo reclamó antes, o ya terminó)
     */
    public Optional<Job> claim(UUID id, java.time.Instant now, java.time.Instant leaseExpiresAt) {
        return jdbc.sql("""
                UPDATE jobs
                   SET status = 'RUNNING', attempts = attempts + 1, updated_at = :now,
                       started_at = COALESCE(started_at, :now), lease_expires_at = :leaseExpiresAt
                 WHERE id = :id AND status IN ('QUEUED', 'RETRYING')
             RETURNING *
                """)
                .param("id", id)
                .param("now", utc(now))
                .param("leaseExpiresAt", utc(leaseExpiresAt))
                .query(JobRepository::map)
                .optional();
    }

    /**
     * Cierra un intento: guarda el estado resultante ({@code COMPLETED}, {@code FAILED} o {@code RETRYING})
     * solo si el trabajo sigue en {@code RUNNING} <b>y</b> en
     * el mismo intento ({@code finished.attempts()}). Un worker rezagado, cuyo intento ya fue relevado,
     * no pisa el resultado del nuevo.
     *
     * @return {@code true} si se guardó
     */
    public boolean finishAttempt(Job finished) {
        return jdbc.sql("""
                UPDATE jobs
                   SET status = :status, updated_at = :updatedAt, finished_at = :finishedAt,
                       result = :result, error = :error, lease_expires_at = NULL
                 WHERE id = :id AND status = 'RUNNING' AND attempts = :attempts
                """)
                .param("id", finished.id())
                .param("status", finished.status().name())
                .param("updatedAt", utc(finished.updatedAt()))
                .param("finishedAt", utc(finished.finishedAt()))
                .param("result", finished.result())
                .param("error", finished.error())
                .param("attempts", finished.attempts())
                .update() == 1;
    }

    /**
     * Prolonga el lease del intento en curso hasta {@code leaseExpiresAt}. Es el latido con el que el
     * worker demuestra que sigue vivo.
     *
     * @return {@code true} si se renovó; {@code false} si el intento ya no está en curso (el trabajo fue
     *         recuperado por vencimiento, o terminó)
     */
    public boolean renewLease(UUID id, int attempts, java.time.Instant now, java.time.Instant leaseExpiresAt) {
        return jdbc.sql("""
                UPDATE jobs
                   SET lease_expires_at = :leaseExpiresAt, updated_at = :now
                 WHERE id = :id AND status = 'RUNNING' AND attempts = :attempts
                """)
                .param("id", id)
                .param("attempts", attempts)
                .param("now", utc(now))
                .param("leaseExpiresAt", utc(leaseExpiresAt))
                .update() == 1;
    }

    /** Trabajos {@code RUNNING} cuyo lease ya venció a {@code now}, los más antiguos primero. */
    public List<Job> findExpired(java.time.Instant now, int limit) {
        return jdbc.sql("""
                SELECT * FROM jobs
                 WHERE status = 'RUNNING' AND lease_expires_at <= :now
                 ORDER BY lease_expires_at, id
                 LIMIT :limit
                """)
                .param("now", utc(now))
                .param("limit", limit)
                .query(JobRepository::map)
                .list();
    }

    /**
     * Como {@link #finishAttempt}, pero para el recuperador: además exige que el lease <b>siga vencido</b>
     * a {@code now}. Si el worker renovó el lease entre que se listó el trabajo y se llegó aquí, el
     * trabajo sigue activo y no se toca.
     *
     * @return {@code true} si se guardó
     */
    public boolean finishExpiredAttempt(Job finished, java.time.Instant now) {
        return jdbc.sql("""
                UPDATE jobs
                   SET status = :status, updated_at = :updatedAt, finished_at = :finishedAt,
                       result = :result, error = :error, lease_expires_at = NULL
                 WHERE id = :id AND status = 'RUNNING' AND attempts = :attempts AND lease_expires_at <= :now
                """)
                .param("id", finished.id())
                .param("status", finished.status().name())
                .param("updatedAt", utc(finished.updatedAt()))
                .param("finishedAt", utc(finished.finishedAt()))
                .param("result", finished.result())
                .param("error", finished.error())
                .param("attempts", finished.attempts())
                .param("now", utc(now))
                .update() == 1;
    }

    /**
     * Devuelve a {@code QUEUED} un trabajo {@code FAILED} (reintento manual). Exige que siga en
     * {@code FAILED} con el mismo número de intentos que se leyó, así que de dos peticiones simultáneas solo
     * una prospera.
     *
     * @param requeued          el trabajo ya transformado con {@link Job#requeued}
     * @param attemptsWhenRead  intentos que tenía el {@code FAILED} leído
     * @return {@code true} si pasó a {@code QUEUED}
     */
    public boolean requeueFailed(Job requeued, int attemptsWhenRead) {
        return jdbc.sql("""
                UPDATE jobs
                   SET status = 'QUEUED', attempts = 0, finished_at = NULL, updated_at = :updatedAt
                 WHERE id = :id AND status = 'FAILED' AND attempts = :attempts
                """)
                .param("id", requeued.id())
                .param("updatedAt", utc(requeued.updatedAt()))
                .param("attempts", attemptsWhenRead)
                .update() == 1;
    }

    public void insertRetry(JobRetry retry) {
        jdbc.sql("""
                INSERT INTO job_retries (id, job_id, requested_at, attempts, error)
                VALUES (:id, :jobId, :requestedAt, :attempts, :error)
                """)
                .param("id", retry.id())
                .param("jobId", retry.jobId())
                .param("requestedAt", utc(retry.requestedAt()))
                .param("attempts", retry.attempts())
                .param("error", retry.error())
                .update();
    }

    /** Reintentos manuales del trabajo, del más antiguo al más reciente. */
    public List<JobRetry> findRetries(UUID jobId) {
        return jdbc.sql("SELECT * FROM job_retries WHERE job_id = :jobId ORDER BY requested_at, id")
                .param("jobId", jobId)
                .query((rs, i) -> new JobRetry(rs.getObject("id", UUID.class), rs.getObject("job_id", UUID.class),
                        rs.getObject("requested_at", OffsetDateTime.class).toInstant(), rs.getInt("attempts"),
                        rs.getString("error")))
                .list();
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
                rs.getString("error"),
                rs.getInt("attempts"));
    }

    private static java.time.Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(java.time.Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }
}
