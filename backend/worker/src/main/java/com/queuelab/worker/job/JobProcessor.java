package com.queuelab.worker.job;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.queuelab.core.job.Job;
import com.queuelab.core.job.JobRepository;
import com.queuelab.core.job.JobStatus;
import com.queuelab.core.messaging.JobMessage;

/**
 * Lleva un trabajo por {@code QUEUED → RUNNING → COMPLETED/FAILED}, persistiendo cada paso en PostgreSQL
 * (la fuente de verdad que lee la API). Cada transición es un {@code UPDATE} condicionado al estado
 * anterior, así dos workers no ejecutan el mismo trabajo.
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
        Job job = jobs.findById(message.jobId()).orElseThrow(() -> new UnknownJobException(message.jobId()));

        // La entrega es «al menos una vez»: un duplicado, o un trabajo que otro worker ya tomó, se ignora.
        if (job.status() != JobStatus.QUEUED) {
            log.info("Trabajo {} ignorado: ya está en {}", job.id(), job.status());
            return;
        }
        Job running = job.transitionTo(JobStatus.RUNNING, now());
        if (!jobs.update(running, JobStatus.QUEUED)) {
            log.info("Trabajo {} ignorado: otro proceso lo tomó antes", job.id());
            return;
        }

        Job finished;
        try {
            finished = running.completed(executor.execute(running), now());
        } catch (JobExecutionException e) {
            log.warn("El trabajo {} falló: {}", job.id(), e.getMessage());
            finished = running.failed(e.getMessage(), now());
        } catch (RuntimeException e) {
            log.error("El trabajo {} falló de forma inesperada", job.id(), e);
            finished = running.failed(UNEXPECTED_ERROR, now());
        }
        if (!jobs.update(finished, JobStatus.RUNNING)) {
            log.warn("No se pudo guardar el estado final de {}: ya no estaba en RUNNING", job.id());
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
