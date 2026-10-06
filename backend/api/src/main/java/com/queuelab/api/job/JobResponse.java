package com.queuelab.api.job;

import java.time.Instant;
import java.util.UUID;

import com.queuelab.core.job.Job;
import com.queuelab.core.job.JobStatus;

/** Representación pública de un trabajo. */
public record JobResponse(
        UUID id,
        String type,
        JobStatus status,
        Instant createdAt,
        Instant updatedAt,
        Instant startedAt,
        Instant finishedAt,
        String result,
        String error,
        int attempts,
        ResultFile resultFile) {

    /** Sin fichero de resultado: para trabajos que acaban de crearse o reponerse en la cola. */
    public static JobResponse from(Job job) {
        return from(job, null);
    }

    /** @param resultFile fichero descargable, o {@code null} si el trabajo no lo tiene (todavía) */
    public static JobResponse from(Job job, ResultFile resultFile) {
        return new JobResponse(job.id(), job.type(), job.status(), job.createdAt(), job.updatedAt(),
                job.startedAt(), job.finishedAt(), job.result(), job.error(), job.attempts(), resultFile);
    }
}
