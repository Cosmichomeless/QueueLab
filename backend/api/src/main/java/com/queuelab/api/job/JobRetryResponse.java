package com.queuelab.api.job;

import java.time.Instant;

import com.queuelab.core.job.JobRetry;

/** Un reintento manual del historial: cuándo se pidió y qué había consumido el trabajo hasta entonces. */
public record JobRetryResponse(Instant requestedAt, int attempts, String error) {

    public static JobRetryResponse from(JobRetry retry) {
        return new JobRetryResponse(retry.requestedAt(), retry.attempts(), retry.error());
    }
}
