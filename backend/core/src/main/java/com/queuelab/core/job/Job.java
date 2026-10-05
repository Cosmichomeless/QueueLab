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
 * @param result     resumen del resultado de un trabajo {@code COMPLETED}; {@code null} en el resto
 * @param error      resumen del error de un trabajo {@code FAILED}; nunca lleva trazas ni datos sensibles
 */
public record Job(
        UUID id,
        String type,
        JobStatus status,
        Instant createdAt,
        Instant updatedAt,
        Instant startedAt,
        Instant finishedAt,
        String result,
        String error) {

    /** Longitud máxima de {@code result} y {@code error}, igual que las columnas. */
    public static final int RESULT_MAX_LENGTH = 1000;
    public static final int ERROR_MAX_LENGTH = 500;

    public Job {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (type.isBlank()) {
            throw new IllegalArgumentException("type no puede estar vacío");
        }
        if (result != null && result.length() > RESULT_MAX_LENGTH) {
            throw new IllegalArgumentException("result supera " + RESULT_MAX_LENGTH + " caracteres");
        }
        if (error != null && error.length() > ERROR_MAX_LENGTH) {
            throw new IllegalArgumentException("error supera " + ERROR_MAX_LENGTH + " caracteres");
        }
    }

    /** Trabajo sin resultado ni error. */
    public Job(UUID id, String type, JobStatus status, Instant createdAt, Instant updatedAt, Instant startedAt,
            Instant finishedAt) {
        this(id, type, status, createdAt, updatedAt, startedAt, finishedAt, null, null);
    }

    /** Crea un trabajo nuevo en {@code QUEUED}. */
    public static Job queued(UUID id, String type, Instant now) {
        return new Job(id, type, JobStatus.QUEUED, now, now, null, null, null, null);
    }

    /** Pasa al estado indicado o lanza {@link InvalidJobTransitionException}. */
    public Job transitionTo(JobStatus target, Instant now) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidJobTransitionException(id, status, target);
        }
        Instant started = startedAt == null && target == JobStatus.RUNNING ? now : startedAt;
        Instant finished = target.isTerminal() ? now : finishedAt;
        return new Job(id, type, target, createdAt, now, started, finished, result, error);
    }

    /** {@code RUNNING → COMPLETED} guardando un resumen del resultado (se recorta a {@link #RESULT_MAX_LENGTH}). */
    public Job completed(String result, Instant now) {
        Job done = transitionTo(JobStatus.COMPLETED, now);
        return new Job(id, type, done.status, createdAt, now, done.startedAt, done.finishedAt,
                truncate(result, RESULT_MAX_LENGTH), null);
    }

    /** {@code RUNNING → FAILED} guardando un resumen del error (se recorta a {@link #ERROR_MAX_LENGTH}). */
    public Job failed(String error, Instant now) {
        Job done = transitionTo(JobStatus.FAILED, now);
        return new Job(id, type, done.status, createdAt, now, done.startedAt, done.finishedAt,
                null, truncate(error, ERROR_MAX_LENGTH));
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
