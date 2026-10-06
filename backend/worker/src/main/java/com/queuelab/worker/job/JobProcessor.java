package com.queuelab.worker.job;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.queuelab.core.job.Job;
import com.queuelab.core.job.JobRepository;
import com.queuelab.core.logging.LogContext;
import com.queuelab.core.messaging.JobMessage;
import com.queuelab.core.outbox.OutboxEvent;
import com.queuelab.core.outbox.OutboxRepository;
import com.queuelab.core.tracing.JobTracing;
import com.queuelab.worker.metrics.WorkerMetrics;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;

/**
 * Lleva un trabajo por {@code QUEUED → RUNNING → COMPLETED/FAILED}, persistiendo cada paso en PostgreSQL
 * (la fuente de verdad que lee la API). El paso a {@code RUNNING} es un reclamo atómico
 * ({@link JobRepository#claim}) y el cierre queda atado al número de intento, así dos workers no
 * ejecutan el mismo intento ni uno rezagado pisa el resultado de otro. Un trabajo que agota sus
 * reintentos queda {@code FAILED} y su aviso se publica en la cola dead-letter (vía outbox).
 */
@Component
public class JobProcessor {

    /** Lo que ve la API cuando el fallo no era esperado: el detalle queda solo en el log del worker. */
    static final String UNEXPECTED_ERROR = "Error inesperado durante la ejecución";

    /** Causa que queda registrada cuando el lease vence y el worker se da por caído. */
    public static final String ABANDONED_ERROR = "El worker dejó de responder durante la ejecución";

    private static final Logger log = LoggerFactory.getLogger(JobProcessor.class);

    private final JobRepository jobs;
    private final OutboxRepository outbox;
    private final JobExecutor executor;
    private final RetryPolicy retryPolicy;
    private final LeasePolicy leasePolicy;
    private final ScheduledExecutorService leaseRenewer;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final WorkerMetrics metrics;
    private final JobTracing tracing;

    JobProcessor(JobRepository jobs, OutboxRepository outbox, JobExecutor executor, RetryPolicy retryPolicy,
            LeasePolicy leasePolicy, ScheduledExecutorService leaseRenewer, PlatformTransactionManager transactions,
            Clock clock, WorkerMetrics metrics, JobTracing tracing) {
        this.jobs = jobs;
        this.outbox = outbox;
        this.executor = executor;
        this.retryPolicy = retryPolicy;
        this.leasePolicy = leasePolicy;
        this.leaseRenewer = leaseRenewer;
        this.transaction = new TransactionTemplate(transactions);
        this.clock = clock;
        this.metrics = metrics;
        this.tracing = tracing;
    }

    /** @throws UnknownJobException si el mensaje apunta a un trabajo que no existe */
    public void process(JobMessage message) {
        // La entrega es «al menos una vez»: un duplicado, o un trabajo que otro worker ya tomó, no se
        // ejecuta de nuevo. El reclamo es atómico, así que solo un worker recibe cada intento.
        Instant claimedAt = now();
        Job running = jobs.claim(message.jobId(), claimedAt, claimedAt.plus(leasePolicy.duration())).orElse(null);
        if (running == null) {
            Job current = jobs.findById(message.jobId()).orElseThrow(() -> new UnknownJobException(message.jobId()));
            log.info("Trabajo {} ignorado: no está pendiente de ejecución (está en {})", current.id(), current.status());
            return;
        }

        log.info("Trabajo {} en ejecución (tipo {}, intento {})", running.id(), running.type(), running.attempts());
        if (running.attempts() == 1) {
            // Solo el primer intento: en los reintentos la espera es la del backoff, no una señal de saturación.
            metrics.waited(running.type(), Duration.between(running.createdAt(), claimedAt));
        }
        ScheduledFuture<?> heartbeat = startHeartbeat(running);
        long startedNanos = System.nanoTime();
        String outcome = WorkerMetrics.OUTCOME_FAILED;
        try {
            finish(running, running.completed(executeTraced(running), now()));
            outcome = WorkerMetrics.OUTCOME_COMPLETED;
            log.info("Trabajo {} completado (intento {})", running.id(), running.attempts());
        } catch (TransientJobException e) {
            log.warn("El trabajo {} falló de forma transitoria (intento {}): {}", running.id(), running.attempts(),
                    e.getMessage());
            outcome = retryPolicy.hasAttemptsLeft(running.attempts())
                    ? WorkerMetrics.OUTCOME_RETRY : WorkerMetrics.OUTCOME_DEAD_LETTER;
            retryOrFail(running, e.getMessage(), false);
        } catch (JobExecutionException e) {
            log.warn("El trabajo {} falló (intento {}): {}", running.id(), running.attempts(), e.getMessage());
            finish(running, running.failed(e.getMessage(), now()));
        } catch (RuntimeException e) {
            log.error("El trabajo {} falló de forma inesperada (intento {})", running.id(), running.attempts(), e);
            finish(running, running.failed(UNEXPECTED_ERROR, now()));
        } finally {
            heartbeat.cancel(false);
            metrics.executionFinished(running.type(), outcome, Duration.ofNanos(System.nanoTime() - startedNanos));
        }
    }

    /**
     * Ejecuta el trabajo dentro de su propio span ({@code job.execute}), hijo del procesamiento del mensaje: su
     * duración es la del ejecutor, sin el reclamo ni el guardado del resultado.
     */
    private String executeTraced(Job running) {
        Span span = tracing.startChild("job.execute", SpanKind.INTERNAL, Attributes.of(
                AttributeKey.stringKey("queuelab.job.id"), running.id().toString(),
                AttributeKey.stringKey("queuelab.job.type"), running.type(),
                AttributeKey.longKey("queuelab.job.attempt"), (long) running.attempts()));
        try (Scope ignored = span.makeCurrent()) {
            return executor.execute(running);
        } catch (RuntimeException e) {
            span.setStatus(StatusCode.ERROR, e.getClass().getSimpleName());
            throw e;
        } finally {
            span.end();
        }
    }

    /**
     * Resuelve un trabajo cuyo lease venció: el intento perdido cuenta, así que se aplica la política de
     * reintentos (otro intento con espera, o {@code FAILED} + DLQ si no quedan). La escritura exige que el
     * lease siga vencido, de modo que un trabajo que reanudó su latido no se toca.
     *
     * @param abandoned trabajo leído en {@code RUNNING} con el lease vencido
     * @return {@code true} si este proceso lo recuperó; {@code false} si ya no hacía falta (lo renovó su
     *         worker, terminó, o lo recuperó otra instancia)
     */
    public boolean recover(Job abandoned) {
        // No hay petición ni mensaje de por medio: la recuperación abre su propio id de correlación.
        // Tampoco hay traza de la que colgar: abre una propia, y el reintento o aviso a la DLQ que guarde la hereda.
        Span span = tracing.startChild("job.recover", SpanKind.INTERNAL, Attributes.of(
                AttributeKey.stringKey("queuelab.job.id"), abandoned.id().toString(),
                AttributeKey.longKey("queuelab.job.attempt"), (long) abandoned.attempts()));
        try (LogContext.Scope ignored = LogContext.with(LogContext.newCorrelationId(), abandoned.id());
                Scope tracingScope = span.makeCurrent()) {
            log.warn("El trabajo {} perdió su lease en el intento {}: se da por abandonado", abandoned.id(),
                    abandoned.attempts());
            return retryOrFail(abandoned, ABANDONED_ERROR, true);
        } finally {
            span.end();
        }
    }

    /** Renueva el lease cada tercio de su duración mientras dure la ejecución. */
    private ScheduledFuture<?> startHeartbeat(Job running) {
        long period = leasePolicy.renewInterval().toMillis();
        // El MDC es del hilo: el latido corre en otro y debe llevar el mismo contexto que el trabajo.
        String correlationId = LogContext.correlationId();
        return leaseRenewer.scheduleAtFixedRate(() -> {
            try (LogContext.Scope ignored = LogContext.with(correlationId, running.id())) {
                Instant now = now();
                if (!jobs.renewLease(running.id(), running.attempts(), now, now.plus(leasePolicy.duration()))) {
                    log.warn("No se pudo renovar el lease de {}: el intento {} ya no está en curso",
                            running.id(), running.attempts());
                }
            } catch (RuntimeException e) {
                log.warn("Fallo renovando el lease de {}; se reintenta en el próximo latido", running.id(), e);
            }
        }, period, period, TimeUnit.MILLISECONDS);
    }

    /**
     * Fallo transitorio (o intento abandonado): otro intento con espera exponencial, o {@code FAILED} si ya no
     * quedan. Con {@code expiredLease} la escritura exige además que el lease siga vencido.
     *
     * @return {@code true} si se guardó el nuevo estado
     */
    private boolean retryOrFail(Job running, String error, boolean expiredLease) {
        if (!retryPolicy.hasAttemptsLeft(running.attempts())) {
            log.warn("El trabajo {} agotó sus {} intentos", running.id(), retryPolicy.maxAttempts());
            return deadLetter(running, error, expiredLease);
        }
        Instant now = now();
        Duration delay = retryPolicy.delayAfter(running.attempts());
        Job retrying = running.retrying(error, now);
        // El estado y el evento que lo reactivará se confirman juntos: no puede quedar un RETRYING sin mensaje.
        Boolean scheduled = transaction.execute(status -> {
            if (!save(retrying, now, expiredLease)) {
                return false;
            }
            outbox.insert(OutboxEvent.jobQueued(retrying, now), now.plus(delay));
            return true;
        });
        if (Boolean.TRUE.equals(scheduled)) {
            metrics.retryScheduled(running.type(),
                    expiredLease ? WorkerMetrics.CAUSE_LEASE_EXPIRED : WorkerMetrics.CAUSE_TRANSIENT);
            log.info("El trabajo {} se reintentará en {} (intento {} de {})", running.id(), delay,
                    running.attempts() + 1, retryPolicy.maxAttempts());
            return true;
        }
        log.warn("No se pudo programar el reintento de {}: el intento {} ya no está en curso",
                running.id(), running.attempts());
        return false;
    }

    private boolean save(Job finished, Instant now, boolean expiredLease) {
        return expiredLease ? jobs.finishExpiredAttempt(finished, now) : jobs.finishAttempt(finished);
    }

    /**
     * Reintentos agotados: {@code FAILED} y, en la misma transacción, el evento que lleva el mensaje a la
     * cola dead-letter para inspeccionarlo. Así no puede haber un {@code FAILED} agotado sin aviso en la DLQ.
     */
    private boolean deadLetter(Job running, String error, boolean expiredLease) {
        Instant now = now();
        Job failed = running.failed(error, now);
        Boolean recorded = transaction.execute(status -> {
            if (!save(failed, now, expiredLease)) {
                return false;
            }
            outbox.insert(OutboxEvent.deadLettered(failed, now));
            return true;
        });
        if (!Boolean.TRUE.equals(recorded)) {
            log.warn("No se pudo guardar el estado final de {}: el intento {} ya no está en curso",
                    running.id(), running.attempts());
            return false;
        }
        metrics.deadLettered(running.type(),
                expiredLease ? WorkerMetrics.CAUSE_LEASE_EXPIRED : WorkerMetrics.CAUSE_TRANSIENT);
        return true;
    }

    private void finish(Job running, Job finished) {
        if (!jobs.finishAttempt(finished)) {
            log.warn("No se pudo guardar el estado final de {}: el intento {} ya no está en curso",
                    running.id(), running.attempts());
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
