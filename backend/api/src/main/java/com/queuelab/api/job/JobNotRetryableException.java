package com.queuelab.api.job;

import java.util.UUID;

import com.queuelab.core.job.JobStatus;

/** Se pidió reintentar un trabajo que no está en {@code FAILED}. */
public class JobNotRetryableException extends RuntimeException {

    public JobNotRetryableException(UUID id, JobStatus status) {
        super("Solo se pueden reintentar trabajos en FAILED; el trabajo " + id + " está en " + status);
    }
}
