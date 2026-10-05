package com.queuelab.core.job;

import java.util.UUID;

/** Se intentó una transición de estado que el ciclo de vida no permite. */
public class InvalidJobTransitionException extends RuntimeException {

    private final UUID jobId;
    private final JobStatus from;
    private final JobStatus to;

    public InvalidJobTransitionException(UUID jobId, JobStatus from, JobStatus to) {
        super("Transición no permitida para el job %s: %s → %s".formatted(jobId, from, to));
        this.jobId = jobId;
        this.from = from;
        this.to = to;
    }

    public UUID jobId() {
        return jobId;
    }

    public JobStatus from() {
        return from;
    }

    public JobStatus to() {
        return to;
    }
}
