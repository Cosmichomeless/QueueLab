package com.queuelab.api.outbox;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.queuelab.core.logging.LogContext;
import com.queuelab.core.messaging.JobMessageCodec;
import com.queuelab.core.messaging.JobMessagingTopology;
import com.queuelab.core.outbox.OutboxEvent;
import com.queuelab.core.outbox.OutboxRepository;

/**
 * Publica en RabbitMQ los eventos pendientes del outbox y solo los marca como publicados cuando el
 * broker los confirma (publisher confirms). Si RabbitMQ no responde, el evento sigue pendiente con
 * {@code attempts} y {@code last_error} actualizados, y se reintenta en la siguiente pasada.
 *
 * <p>El destino depende del tipo de evento ({@link JobMessagingTopology#routeFor}): los trabajos listos van
 * a la cola principal y los que agotaron sus reintentos, a la dead-letter.
 *
 * <p>La entrega es <b>al menos una vez</b>: si el proceso cae entre la confirmación y el guardado de
 * {@code published_at}, el mensaje se republica. El consumidor debe tolerar duplicados.
 */
@Component
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    private final OutboxRepository outbox;
    private final RabbitTemplate rabbit;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final int batchSize;
    private final Duration confirmTimeout;

    OutboxDispatcher(OutboxRepository outbox, RabbitTemplate rabbit, PlatformTransactionManager transactions,
            Clock clock, @Value("${queuelab.outbox.batch-size:50}") int batchSize,
            @Value("${queuelab.outbox.confirm-timeout:5s}") Duration confirmTimeout) {
        this.outbox = outbox;
        this.rabbit = rabbit;
        this.transaction = new TransactionTemplate(transactions);
        this.clock = clock;
        this.batchSize = batchSize;
        this.confirmTimeout = confirmTimeout;
    }

    /** Una pasada: publica hasta {@code batch-size} eventos pendientes y devuelve cuántos se confirmaron. */
    public int dispatchPending() {
        Integer published = transaction.execute(status -> {
            List<OutboxEvent> pending = outbox.findPending(batchSize);
            int confirmed = 0;
            for (OutboxEvent event : pending) {
                // Cada evento se publica con el id de correlación de la petición que lo originó: sus logs y
                // la cabecera del mensaje lo llevan, así el worker y la API hablan del mismo trabajo.
                try (LogContext.Scope ignored = LogContext.with(correlationOf(event), event.jobId())) {
                    Outcome outcome = publish(event);
                    if (outcome == Outcome.CONFIRMED) {
                        outbox.markPublished(event.id(), clock.instant());
                        log.info("Evento {} publicado en {} (trabajo {})", event.eventType(),
                                JobMessagingTopology.routeFor(event.eventType()).exchange(), event.jobId());
                        confirmed++;
                    } else {
                        // Con el broker caído o lento no tiene sentido esperar el resto del lote.
                        if (outcome == Outcome.BROKER_UNAVAILABLE) {
                            break;
                        }
                    }
                }
            }
            return confirmed;
        });
        return published == null ? 0 : published;
    }

    /** El id del evento; los anteriores a V14 no lo tienen y se publican con uno nuevo. */
    private static String correlationOf(OutboxEvent event) {
        return event.correlationId() != null ? event.correlationId() : LogContext.newCorrelationId();
    }

    private enum Outcome { CONFIRMED, REJECTED, BROKER_UNAVAILABLE }

    private Outcome publish(OutboxEvent event) {
        CorrelationData correlation = new CorrelationData(event.id().toString());
        JobMessagingTopology.Route route;
        try {
            route = JobMessagingTopology.routeFor(event.eventType());
        } catch (IllegalArgumentException e) {
            return fail(event, Outcome.REJECTED, e.getMessage());
        }
        try {
            rabbit.send(route.exchange(), route.routingKey(), JobMessageCodec.encodeJson(event.payload(), LogContext.correlationId()),
                    correlation);
            CorrelationData.Confirm confirm = correlation.getFuture().get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!confirm.isAck()) {
                return fail(event, Outcome.REJECTED, "El broker rechazó el mensaje (nack): " + confirm.getReason());
            }
            if (correlation.getReturned() != null) {
                return fail(event, Outcome.REJECTED, "El broker no encontró cola para el mensaje");
            }
            return Outcome.CONFIRMED;
        } catch (TimeoutException e) {
            return fail(event, Outcome.BROKER_UNAVAILABLE, "Sin confirmación del broker en " + confirmTimeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fail(event, Outcome.BROKER_UNAVAILABLE, "Interrumpido esperando la confirmación");
        } catch (ExecutionException | AmqpException e) {
            return fail(event, Outcome.BROKER_UNAVAILABLE, "RabbitMQ no disponible: " + e.getClass().getSimpleName());
        }
    }

    private Outcome fail(OutboxEvent event, Outcome outcome, String error) {
        log.warn("Evento {} (trabajo {}) sigue pendiente, intento {}: {}", event.id(), event.jobId(),
                event.attempts() + 1, error);
        outbox.recordFailure(event.id(), error);
        return outcome;
    }
}
