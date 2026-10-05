package com.queuelab.core.outbox;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.queuelab.core.job.Job;
import com.queuelab.core.messaging.JobMessage;
import com.queuelab.core.messaging.JobMessageCodec;

/**
 * Evento pendiente de publicar en RabbitMQ.
 *
 * @param payload     cuerpo JSON del mensaje, tal como lo definió el contrato en el momento de guardarlo
 * @param publishedAt cuando el broker confirmó el mensaje; {@code null} mientras esté pendiente
 * @param attempts    intentos de publicación fallidos hasta ahora
 */
public record OutboxEvent(
        UUID id,
        UUID jobId,
        String eventType,
        String payload,
        Instant createdAt,
        Instant publishedAt,
        int attempts) {

    /** Evento de un trabajo recién encolado. */
    public static final String JOB_QUEUED = "JOB_QUEUED";

    public OutboxEvent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /** Evento «trabajo encolado» con el mensaje del contrato ya serializado. */
    public static OutboxEvent jobQueued(Job job, Instant now) {
        String payload = JobMessageCodec.toJson(JobMessage.forJob(job.id()));
        return new OutboxEvent(UUID.randomUUID(), job.id(), JOB_QUEUED, payload, now, null, 0);
    }

    public boolean isPublished() {
        return publishedAt != null;
    }
}
