package com.queuelab.worker.job;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.queuelab.core.job.Job;
import com.queuelab.core.job.JobRepository;
import com.queuelab.core.messaging.JobMessage;
import com.queuelab.core.outbox.OutboxEvent;
import com.queuelab.core.outbox.OutboxRepository;

/**
 * Lleva un trabajo por {@code QUEUED → RUNNING → COMPLETED/FAILED}, persistiendo cada paso en PostgreSQL
 * (la fuente de verdad que lee la API). El paso a {@code RUNNING} es un reclamo atómico
 * ({@link JobRepository#claim}) y el cierre queda atado al número de intento, así dos workers no
 * ejecutan el mismo intento ni uno rezagado pisa el resultado de otro.
 */
@Component
public class JobProcessor {

    /** Lo que ve la API cuando el fallo no era esperado: el detalle queda solo en el log del worker. */
    static final String UNEXPECTED_ERROR = "Error inesperado durante la ejecución";

    private static final Logger log = LoggerFactory.getLogger(JobProcessor.class);

    private final JobRepository jobs;
    private final OutboxRepository outbox;
    private final JobExecutor executor;
    private final RetryPolicy retryPolicy;
    private final TransactionTemplate transaction;
    private final Clock clock;

    JobProcessor(JobRepository jobs, OutboxRepository outbox, JobExecutor executor, RetryPolicy retryPolicy,
            PlatformTransactionManager transactions, Clock clock) {
        this.jobs = jobs;
        this.outbox = outbox;
        this.executor = executor;
        this.retryPolicy = retryPolicy;
        this.transaction = new TransactionTemplate(transactions);
        this.clock = clock;
    }

    /** @throws UnknownJobException si el mensaje apunta a un trabajo que no existe */
    public void process(JobMessage message) {
        // La entrega es «al menos una vez»: un duplicado, o un trabajo que otro worker ya tomó, no se
        // ejecuta de nuevo. El reclamo es atómico, así que solo un worker recibe cada intento.
        Job running = jobs.claim(message.jobId(), now()).orElse(null);
        if (running == null) {
            Job current = jobs.findById(message.jobId()).orElseThrow(() -> new UnknownJobException(message.jobId()));
            log.info("Trabajo {} ignorado: no está pendiente de ejecución (está en {})", current.id(), current.status());
            return;
        }

        try {
            finish(running, running.completed(executor.execute(running), now()));
        } catch (TransientJobException e) {
            log.warn("El trabajo {} falló de forma transitoria (intento {}): {}", running.id(), running.attempts(),
                    e.getMessage());
            retryOrFail(running, e.getMessage());
        } catch (JobExecutionException e) {
            log.warn("El trabajo {} falló (intento {}): {}", running.id(), running.attempts(), e.getMessage());
            finish(running, running.failed(e.getMessage(), now()));
        } catch (RuntimeException e) {
            log.error("El trabajo {} falló de forma inesperada (intento {})", running.id(), running.attempts(), e);
            finish(running, running.failed(UNEXPECTED_ERROR, now()));
        }
    }

    /** Fallo transitorio: otro intento con espera exponencial, o {@code FAILED} si ya no quedan. */
    private void retryOrFail(Job running, String error) {
        if (!retryPolicy.hasAttemptsLeft(running.attempts())) {
            log.warn("El trabajo {} agotó sus {} intentos", running.id(), retryPolicy.maxAttempts());
            finish(running, running.failed(error, now()));
            return;
        }
        Instant now = now();
        Duration delay = retryPolicy.delayAfter(running.attempts());
        Job retrying = running.retrying(error, now);
        // El estado y el evento que lo reactivará se confirman juntos: no puede quedar un RETRYING sin mensaje.
        Boolean scheduled = transaction.execute(status -> {
            if (!jobs.finishAttempt(retrying)) {
                return false;
            }
            outbox.insert(OutboxEvent.jobQueued(retrying, now), now.plus(delay));
            return true;
        });
        if (Boolean.TRUE.equals(scheduled)) {
            log.info("El trabajo {} se reintentará en {} (intento {} de {})", running.id(), delay,
                    running.attempts() + 1, retryPolicy.maxAttempts());
        } else {
            log.warn("No se pudo programar el reintento de {}: el intento {} ya no está en curso",
                    running.id(), running.attempts());
        }
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
