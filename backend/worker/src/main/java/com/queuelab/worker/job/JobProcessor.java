package com.queuelab.worker.job;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.queuelab.core.job.Job;
import com.queuelab.core.job.JobRepository;
import com.queuelab.core.messaging.JobMessage;

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
    private final JobExecutor executor;
    private final Clock clock;

    JobProcessor(JobRepository jobs, JobExecutor executor, Clock clock) {
        this.jobs = jobs;
        this.executor = executor;
        this.clock = clock;
    }

    /** @throws UnknownJobException si el mensaje apunta a un trabajo que no existe */
    public void process(JobMessage message) {
        // La entrega es «al menos una vez»: un duplicado, o un trabajo que otro worker ya tomó, no se
        // ejecuta de nuevo. El reclamo es atómico, así que solo un worker recibe cada intento.
        Job running = jobs.claim(message.jobId(), now()).orElse(null);
        if (running == null) {
            Job current = jobs.findById(message.jobId()).orElseThrow(() -> new UnknownJobException(message.jobId()));
            log.info("Trabajo {} ignorado: no está en QUEUED (está en {})", current.id(), current.status());
            return;
        }

        Job finished;
        try {
            finished = running.completed(executor.execute(running), now());
        } catch (JobExecutionException e) {
            log.warn("El trabajo {} falló (intento {}): {}", running.id(), running.attempts(), e.getMessage());
            finished = running.failed(e.getMessage(), now());
        } catch (RuntimeException e) {
            log.error("El trabajo {} falló de forma inesperada (intento {})", running.id(), running.attempts(), e);
            finished = running.failed(UNEXPECTED_ERROR, now());
        }
        if (!jobs.finishAttempt(finished)) {
            log.warn("No se pudo guardar el estado final de {}: el intento {} ya no está en curso",
                    running.id(), running.attempts());
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
