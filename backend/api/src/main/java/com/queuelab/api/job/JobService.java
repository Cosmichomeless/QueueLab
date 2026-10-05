package com.queuelab.api.job;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.queuelab.core.job.Job;
import com.queuelab.core.job.JobCursor;
import com.queuelab.core.job.JobRepository;
import com.queuelab.core.job.JobRetry;
import com.queuelab.core.job.JobStatus;
import com.queuelab.core.job.StoredSubmission;
import com.queuelab.core.outbox.OutboxEvent;
import com.queuelab.core.outbox.OutboxRepository;

@Service
public class JobService {

    private final JobRepository jobs;
    private final Clock clock;
    private final OutboxRepository outbox;
    private final JobTypeValidator typeValidator;

    public JobService(JobRepository jobs, OutboxRepository outbox, Clock clock, JobTypeValidator typeValidator) {
        this.jobs = jobs;
        this.outbox = outbox;
        this.clock = clock;
        this.typeValidator = typeValidator;
    }

    public Job get(UUID id) {
        return jobs.findById(id).orElseThrow(() -> new JobNotFoundException(id));
    }

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    /** Lista trabajos del más reciente al más antiguo; {@code status} y {@code cursor} son opcionales. */
    public JobPage list(JobStatus status, String cursor, int limit) {
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new InvalidRequestException("El parámetro 'limit' debe estar entre 1 y " + MAX_PAGE_SIZE);
        }
        JobCursor after = cursor == null ? null : CursorCodec.decode(cursor);

        // Se pide uno de más para saber si existe una página siguiente.
        List<Job> found = jobs.findPage(status, after, limit + 1);
        boolean hasMore = found.size() > limit;
        List<Job> page = hasMore ? found.subList(0, limit) : found;

        String next = hasMore ? CursorCodec.encode(JobCursor.of(page.getLast())) : null;
        return new JobPage(page.stream().map(JobResponse::from).toList(), next);
    }

    /**
     * Registra el trabajo en {@code QUEUED} y su evento de outbox en una sola transacción: o se
     * guardan los dos o ninguno. La publicación en RabbitMQ y el procesamiento ocurren fuera de la
     * petición.
     *
     * <p>Con {@code idempotencyKey}, repetir la misma petición devuelve el trabajo ya creado (sin
     * segundo trabajo ni segundo evento) y reutilizar la clave con otra carga lanza
     * {@link IdempotencyConflictException}. Sin clave, cada llamada crea un trabajo nuevo.
     */
    @Transactional
    public Submission submit(String type, String idempotencyKey) {
        typeValidator.validate(type);
        if (idempotencyKey == null) {
            return new Submission(create(type), true);
        }
        IdempotencyKey.validate(idempotencyKey);
        String fingerprint = IdempotencyKey.fingerprint(type);

        var existing = jobs.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), fingerprint);
        }
        // PostgreSQL guarda microsegundos: así lo devuelto coincide con lo almacenado.
        Job job = Job.queued(UUID.randomUUID(), type, clock.instant().truncatedTo(ChronoUnit.MICROS));
        if (jobs.insertIfKeyAbsent(job, idempotencyKey, fingerprint)) {
            outbox.insert(OutboxEvent.jobQueued(job, job.createdAt()));
            return new Submission(job, true);
        }
        // Otra petición con la misma clave se nos adelantó entre la consulta y el insert.
        return replay(jobs.findByIdempotencyKey(idempotencyKey).orElseThrow(), fingerprint);
    }

    /**
     * Reintento manual de un trabajo {@code FAILED}: vuelve a {@code QUEUED} (con el contador de intentos a
     * cero), queda anotado en el historial y se republica por el outbox, todo en una transacción. La
     * transición exige seguir en {@code FAILED}, de modo que una segunda petición simultánea (o un
     * doble clic) recibe {@link JobNotRetryableException} en vez de crear otro envío.
     */
    @Transactional
    public Job retry(UUID id) {
        Job failed = get(id);
        if (failed.status() != JobStatus.FAILED) {
            throw new JobNotRetryableException(id, failed.status());
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Job requeued = failed.requeued(now);
        if (!jobs.requeueFailed(requeued, failed.attempts())) {
            // Otra petición lo reintentó entre la lectura y la escritura.
            throw new JobNotRetryableException(id, get(id).status());
        }
        jobs.insertRetry(new JobRetry(UUID.randomUUID(), id, now, failed.attempts(), failed.error()));
        outbox.insert(OutboxEvent.jobQueued(requeued, now));
        return requeued;
    }

    /** Historial de reintentos manuales del trabajo, del más antiguo al más reciente. */
    public List<JobRetry> retries(UUID id) {
        get(id);
        return jobs.findRetries(id);
    }

    private Submission replay(StoredSubmission stored, String fingerprint) {
        if (!stored.fingerprint().equals(fingerprint)) {
            throw new IdempotencyConflictException();
        }
        return new Submission(stored.job(), false);
    }

    private Job create(String type) {
        Job job = Job.queued(UUID.randomUUID(), type, clock.instant().truncatedTo(ChronoUnit.MICROS));
        jobs.insert(job);
        outbox.insert(OutboxEvent.jobQueued(job, job.createdAt()));
        return job;
    }
}
