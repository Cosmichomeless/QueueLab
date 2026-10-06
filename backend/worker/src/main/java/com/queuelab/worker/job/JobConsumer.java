package com.queuelab.worker.job;

import java.time.Clock;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import com.queuelab.core.logging.LogContext;
import com.queuelab.core.messaging.JobMessage;
import com.queuelab.core.messaging.JobMessageCodec;
import com.queuelab.core.messaging.JobMessagingTopology;
import com.queuelab.core.messaging.MalformedJobMessageException;
import com.queuelab.core.tracing.JobTracing;
import com.queuelab.worker.metrics.WorkerMetrics;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;

/**
 * Consume {@code queuelab.jobs.queued}. Un mensaje malformado o que apunta a un trabajo inexistente se
 * rechaza <b>sin reencolar</b>: RabbitMQ lo desvía a la cola dead-letter y no bloquea a los demás.
 */
@Component
class JobConsumer {

    private static final Logger log = LoggerFactory.getLogger(JobConsumer.class);

    private final JobProcessor processor;
    private final WorkerMetrics metrics;
    private final JobTracing tracing;
    private final Clock clock;

    JobConsumer(JobProcessor processor, WorkerMetrics metrics, JobTracing tracing, Clock clock) {
        this.processor = processor;
        this.metrics = metrics;
        this.tracing = tracing;
        this.clock = clock;
    }

    @RabbitListener(queues = JobMessagingTopology.QUEUE)
    void onMessage(Message message) {
        // La traza continúa la del despachador: la cabecera apunta a su span de publicación. Sin ella (mensajes
        // antiguos o de otro productor) el procesamiento abre una traza propia.
        Instant receivedAt = clock.instant();
        SpanContext parent = JobMessageCodec.traceContextOf(message);
        Instant enqueuedAt = JobMessageCodec.enqueuedAtOf(message);
        if (enqueuedAt != null) {
            Instant waitStart = enqueuedAt.isAfter(receivedAt) ? receivedAt : enqueuedAt; // relojes desfasados
            tracing.start("queue.wait", SpanKind.INTERNAL, parent, waitStart,
                    Attributes.of(AttributeKey.stringKey("messaging.destination.name"), JobMessagingTopology.QUEUE))
                    .end(receivedAt);
        }
        Span span = tracing.start("job.process", SpanKind.CONSUMER, parent, receivedAt, Attributes.of(
                AttributeKey.stringKey("messaging.system"), "rabbitmq",
                AttributeKey.stringKey("messaging.destination.name"), JobMessagingTopology.QUEUE));
        try (Scope ignored = span.makeCurrent()) {
            handle(message, span);
        } catch (RuntimeException e) {
            span.setStatus(StatusCode.ERROR, e.getClass().getSimpleName());
            throw e;
        } finally {
            span.end();
        }
    }

    private void handle(Message message, Span span) {
        // El id de correlación viaja en una cabecera del mensaje; sin ella (mensajes antiguos o de otro
        // productor) el worker genera uno para que sus propios logs sigan siendo correlables.
        String correlationId = JobMessageCodec.correlationIdOf(message);
        if (correlationId == null) {
            correlationId = LogContext.newCorrelationId();
        }
        try (LogContext.Scope ignored = LogContext.with(correlationId, null)) {
            JobMessage job;
            try {
                job = JobMessageCodec.decode(message);
            } catch (MalformedJobMessageException e) {
                metrics.messageRejected(WorkerMetrics.REASON_MALFORMED);
                log.warn("Mensaje malformado enviado a la cola dead-letter: {}", e.getMessage());
                throw new AmqpRejectAndDontRequeueException(e.getMessage(), e);
            }
            span.setAttribute("queuelab.job.id", job.jobId().toString());
            try (LogContext.Scope jobScope = LogContext.with(null, job.jobId())) {
                processor.process(job);
            } catch (UnknownJobException e) {
                metrics.messageRejected(WorkerMetrics.REASON_UNKNOWN_JOB);
                log.warn("Mensaje enviado a la cola dead-letter: {}", e.getMessage());
                throw new AmqpRejectAndDontRequeueException(e.getMessage(), e);
            }
        }
    }
}
