package com.queuelab.core.outbox;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.queuelab.core.job.Job;
import com.queuelab.core.logging.LogContext;
import com.queuelab.core.messaging.DeadLetterMessage;
import com.queuelab.core.messaging.JobMessage;
import com.queuelab.core.messaging.JobMessageCodec;

/**
 * Evento pendiente de publicar en RabbitMQ.
 *
 * @param payload     cuerpo JSON del mensaje, tal como lo definió el contrato en el momento de guardarlo
 * @param publishedAt cuando el broker confirmó el mensaje; {@code null} mientras esté pendiente
 * @param attempts    intentos de publicación fallidos hasta ahora
 * @param correlationId id que une el evento con la petición que lo originó (ver {@link LogContext}); viaja como
 *                    cabecera del mensaje. {@code null} solo en eventos anteriores a V14
 */
public record OutboxEvent(
        UUID id,
        UUID jobId,
        String eventType,
        String payload,
        Instant createdAt,
        Instant publishedAt,
        int attempts,
        String correlationId) {

    /** Evento de un trabajo recién encolado. */
    public static final String JOB_QUEUED = "JOB_QUEUED";

    /** Trabajo que agotó sus reintentos: su mensaje debe llegar a la cola dead-letter. */
    public static final String JOB_DEAD_LETTERED = "JOB_DEAD_LETTERED";

    public OutboxEvent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(createdAt, "createdAt");
        if (correlationId != null && !LogContext.isValid(correlationId)) {
            throw new IllegalArgumentException("correlationId no válido");
        }
    }

    /** Evento sin id de correlación. */
    public OutboxEvent(UUID id, UUID jobId, String eventType, String payload, Instant createdAt, Instant publishedAt,
            int attempts) {
        this(id, jobId, eventType, payload, createdAt, publishedAt, attempts, null);
    }

    /**
     * Evento «trabajo encolado» con el mensaje del contrato ya serializado. Hereda el id de correlación del
     * hilo ({@link LogContext#correlationId()}: la petición HTTP o el mensaje que se está procesando); sin
     * contexto (p. ej. una tarea programada) se crea uno nuevo, de modo que todo mensaje lleva uno.
     */
    public static OutboxEvent jobQueued(Job job, Instant now) {
        String payload = JobMessageCodec.toJson(JobMessage.forJob(job.id()));
        return new OutboxEvent(UUID.randomUUID(), job.id(), JOB_QUEUED, payload, now, null, 0, currentCorrelationId());
    }

    /** Evento «reintentos agotados» de un trabajo ya en {@code FAILED}, con su causa y nº de intentos. */
    public static OutboxEvent deadLettered(Job failed, Instant now) {
        String payload = JobMessageCodec.toJson(
                DeadLetterMessage.forJob(failed.id(), failed.attempts(), failed.error()));
        return new OutboxEvent(UUID.randomUUID(), failed.id(), JOB_DEAD_LETTERED, payload, now, null, 0,
                currentCorrelationId());
    }

    private static String currentCorrelationId() {
        String current = LogContext.correlationId();
        return LogContext.isValid(current) ? current : LogContext.newCorrelationId();
    }

    public boolean isPublished() {
        return publishedAt != null;
    }
}
