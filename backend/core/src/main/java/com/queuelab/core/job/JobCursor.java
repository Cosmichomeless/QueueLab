package com.queuelab.core.job;

import java.time.Instant;
import java.util.UUID;

/** Posición en el listado de trabajos (ordenado por creación descendente, desempate por id). */
public record JobCursor(Instant createdAt, UUID id) {

    public static JobCursor of(Job job) {
        return new JobCursor(job.createdAt(), job.id());
    }
}
