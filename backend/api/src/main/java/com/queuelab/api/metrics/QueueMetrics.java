package com.queuelab.api.metrics;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.stereotype.Component;

import com.queuelab.core.job.JobRepository;
import com.queuelab.core.job.JobStatus;
import com.queuelab.core.messaging.JobMessagingTopology;
import com.queuelab.core.outbox.OutboxRepository;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Estado de la cola visto desde la API: trabajos por estado (PostgreSQL), eventos pendientes del outbox y
 * mensajes/consumidores de las colas de RabbitMQ. Las series son gauges que solo leen una foto guardada en
 * memoria: {@link #refresh()} la actualiza cada pocos segundos, así que un scrape de Prometheus (o cien) nunca
 * lanza una consulta ni habla con el broker. Si una fuente falla, sus gauges pasan a {@code NaN} (sin dato)
 * en lugar de quedarse con un valor viejo que parezca bueno.
 *
 * <p>Etiquetas: {@code status} (los cinco estados del trabajo) y {@code queue} (las dos colas de la topología).
 * Ningún identificador de trabajo entra jamás como etiqueta.
 */
@Component
public class QueueMetrics {

    private static final Logger log = LoggerFactory.getLogger(QueueMetrics.class);

    private static final String SOURCE_JOBS = "jobs";
    private static final String SOURCE_OUTBOX = "outbox";
    private static final String SOURCE_BROKER = "broker";
    private static final List<String> QUEUES = List.of(JobMessagingTopology.QUEUE, JobMessagingTopology.DEAD_LETTER_QUEUE);

    private final JobRepository jobs;
    private final OutboxRepository outbox;
    private final AmqpAdmin amqpAdmin;
    private final Clock clock;

    private volatile Map<JobStatus, Long> jobCounts;
    private volatile OutboxRepository.PendingStats outboxStats;
    private final Map<String, Double> queueMessages = new ConcurrentHashMap<>();
    private final Map<String, Double> queueConsumers = new ConcurrentHashMap<>();
    /** Fuentes que fallaron en la última pasada: el log solo avisa al cambiar, no cada 15 s. */
    private final Map<String, Boolean> failing = new ConcurrentHashMap<>();

    public QueueMetrics(MeterRegistry registry, JobRepository jobs, OutboxRepository outbox, AmqpAdmin amqpAdmin,
            Clock clock) {
        this.jobs = jobs;
        this.outbox = outbox;
        this.amqpAdmin = amqpAdmin;
        this.clock = clock;

        for (JobStatus status : JobStatus.values()) {
            Gauge.builder("queuelab.jobs", () -> {
                        Map<JobStatus, Long> counts = jobCounts;
                        return counts == null ? Double.NaN : counts.getOrDefault(status, 0L);
                    })
                    .description("Trabajos por estado")
                    .tag("status", status.name().toLowerCase(Locale.ROOT))
                    .register(registry);
        }
        Gauge.builder("queuelab.outbox.pending", () -> {
                    OutboxRepository.PendingStats stats = outboxStats;
                    return stats == null ? Double.NaN : stats.count();
                })
                .description("Eventos del outbox listos para publicar y aún sin confirmar por RabbitMQ")
                .register(registry);
        Gauge.builder("queuelab.outbox.oldest.pending.age", () -> {
                    OutboxRepository.PendingStats stats = outboxStats;
                    if (stats == null) {
                        return Double.NaN;
                    }
                    if (stats.oldestCreatedAt() == null) {
                        return 0d;
                    }
                    return Math.max(0d, Duration.between(stats.oldestCreatedAt(), clock.instant()).toMillis() / 1000d);
                })
                .description("Segundos que lleva esperando el evento pendiente más antiguo del outbox")
                .baseUnit("seconds")
                .register(registry);
        for (String queue : QUEUES) {
            queueMessages.put(queue, Double.NaN);
            queueConsumers.put(queue, Double.NaN);
            Gauge.builder("queuelab.queue.messages", () -> queueMessages.get(queue))
                    .description("Mensajes listos en la cola de RabbitMQ")
                    .tag("queue", queue)
                    .register(registry);
            Gauge.builder("queuelab.queue.consumers", () -> queueConsumers.get(queue))
                    .description("Consumidores conectados a la cola de RabbitMQ")
                    .tag("queue", queue)
                    .register(registry);
        }
    }

    /** Vuelve a leer las tres fuentes. Cada una falla por separado sin arrastrar a las demás. */
    public void refresh() {
        jobCounts = read(SOURCE_JOBS, jobs::countByStatus);
        outboxStats = read(SOURCE_OUTBOX, outbox::pendingStats);
        readQueues();
    }

    private void readQueues() {
        try {
            for (String queue : QUEUES) {
                QueueInformation info = amqpAdmin.getQueueInfo(queue);
                // null: la cola no existe (aún no declarada); es un dato real, no un fallo.
                queueMessages.put(queue, info == null ? 0d : info.getMessageCount());
                queueConsumers.put(queue, info == null ? 0d : info.getConsumerCount());
            }
            recovered(SOURCE_BROKER);
        } catch (RuntimeException e) {
            QUEUES.forEach(queue -> {
                queueMessages.put(queue, Double.NaN);
                queueConsumers.put(queue, Double.NaN);
            });
            failed(SOURCE_BROKER, e);
        }
    }

    private <T> T read(String source, Supplier<T> query) {
        try {
            T value = query.get();
            recovered(source);
            return value;
        } catch (RuntimeException e) {
            failed(source, e);
            return null;
        }
    }

    private void failed(String source, RuntimeException e) {
        if (failing.put(source, true) == null) {
            log.warn("Métricas: no se pudo leer «{}»; sus series pasan a NaN hasta que vuelva ({})", source,
                    e.toString());
        }
    }

    private void recovered(String source) {
        if (failing.remove(source) != null) {
            log.info("Métricas: «{}» vuelve a responder", source);
        }
    }
}
