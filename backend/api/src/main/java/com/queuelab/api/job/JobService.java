package com.queuelab.api.job;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.queuelab.core.job.Job;
import com.queuelab.core.job.JobCursor;
import com.queuelab.core.job.JobRepository;
import com.queuelab.core.job.JobStatus;
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
     */
    @Transactional
    public Job submit(String type) {
        typeValidator.validate(type);
        // PostgreSQL guarda microsegundos: así lo devuelto coincide con lo almacenado.
        Job job = Job.queued(UUID.randomUUID(), type, clock.instant().truncatedTo(ChronoUnit.MICROS));
        jobs.insert(job);
        outbox.insert(OutboxEvent.jobQueued(job, job.createdAt()));
        return job;
    }
}
