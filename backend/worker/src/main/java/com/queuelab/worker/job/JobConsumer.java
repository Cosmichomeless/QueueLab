package com.queuelab.worker.job;

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

/**
 * Consume {@code queuelab.jobs.queued}. Un mensaje malformado o que apunta a un trabajo inexistente se
 * rechaza <b>sin reencolar</b>: RabbitMQ lo desvía a la cola dead-letter y no bloquea a los demás.
 */
@Component
class JobConsumer {

    private static final Logger log = LoggerFactory.getLogger(JobConsumer.class);

    private final JobProcessor processor;

    JobConsumer(JobProcessor processor) {
        this.processor = processor;
    }

    @RabbitListener(queues = JobMessagingTopology.QUEUE)
    void onMessage(Message message) {
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
                log.warn("Mensaje malformado enviado a la cola dead-letter: {}", e.getMessage());
                throw new AmqpRejectAndDontRequeueException(e.getMessage(), e);
            }
            try (LogContext.Scope jobScope = LogContext.with(null, job.jobId())) {
                processor.process(job);
            } catch (UnknownJobException e) {
                log.warn("Mensaje enviado a la cola dead-letter: {}", e.getMessage());
                throw new AmqpRejectAndDontRequeueException(e.getMessage(), e);
            }
        }
    }
}
