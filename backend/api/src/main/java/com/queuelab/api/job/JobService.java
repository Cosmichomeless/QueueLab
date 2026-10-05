package com.queuelab.api.job;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.queuelab.core.job.Job;
import com.queuelab.core.job.JobRepository;

@Service
public class JobService {

    private final JobRepository jobs;
    private final Clock clock;

    public JobService(JobRepository jobs, Clock clock) {
        this.jobs = jobs;
        this.clock = clock;
    }

    /** Registra el trabajo en {@code QUEUED}; el procesamiento ocurre fuera de la petición. */
    public Job submit(String type) {
        // PostgreSQL guarda microsegundos: así lo devuelto coincide con lo almacenado.
        Job job = Job.queued(UUID.randomUUID(), type, clock.instant().truncatedTo(ChronoUnit.MICROS));
        jobs.insert(job);
        return job;
    }
}
