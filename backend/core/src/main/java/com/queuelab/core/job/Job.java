package com.queuelab.core.job;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Trabajo asíncrono. Es inmutable: cada cambio de estado devuelve una instancia nueva
 * y las transiciones inválidas se rechazan con {@link InvalidJobTransitionException}.
 *
 * @param startedAt  primera vez que pasó a {@code RUNNING} (no cambia en reintentos)
 * @param finishedAt momento en que llegó a un estado terminal
 */
public record Job(
        UUID id,
        String type,
        JobStatus status,
        Instant createdAt,
        Instant updatedAt,
        Instant startedAt,
        Instant finishedAt) {

    public Job {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (type.isBlank()) {
            throw new IllegalArgumentException("type no puede estar vacío");
        }
    }

    /** Crea un trabajo nuevo en {@code QUEUED}. */
    public static Job queued(UUID id, String type, Instant now) {
        return new Job(id, type, JobStatus.QUEUED, now, now, null, null);
    }

    /** Pasa al estado indicado o lanza {@link InvalidJobTransitionException}. */
    public Job transitionTo(JobStatus target, Instant now) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidJobTransitionException(id, status, target);
        }
        Instant started = startedAt == null && target == JobStatus.RUNNING ? now : startedAt;
        Instant finished = target.isTerminal() ? now : finishedAt;
        return new Job(id, type, target, createdAt, now, started, finished);
    }
}
