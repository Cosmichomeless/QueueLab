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
        String error) {

    public static JobResponse from(Job job) {
        return new JobResponse(job.id(), job.type(), job.status(), job.createdAt(), job.updatedAt(),
                job.startedAt(), job.finishedAt(), job.result(), job.error());
    }
}
