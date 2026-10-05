package com.queuelab.worker.job;

import java.time.Clock;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.queuelab.core.job.Job;
import com.queuelab.core.job.JobRepository;

/**
 * Recupera los trabajos {@code RUNNING} cuyo lease venció: el worker que los ejecutaba cayó (o dejó de
 * latir) y nadie los terminará. Solo toca lo vencido, así que un trabajo cuyo worker sigue renovando el
 * lease nunca se recupera.
 *
 * <p>La política es la de los reintentos ({@link JobProcessor#recover}): el intento perdido cuenta; si
 * quedan intentos, el trabajo pasa a {@code RETRYING} con su espera; si no, a {@code FAILED} y a la DLQ.
 */
@Component
public class AbandonedJobRecoverer {

    private static final Logger log = LoggerFactory.getLogger(AbandonedJobRecoverer.class);

    private final JobRepository jobs;
    private final JobProcessor processor;
    private final Clock clock;
    private final int batchSize;

    AbandonedJobRecoverer(JobRepository jobs, JobProcessor processor, Clock clock,
            @Value("${queuelab.worker.recovery.batch-size:50}") int batchSize) {
        this.jobs = jobs;
        this.processor = processor;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    /** Una pasada: resuelve hasta {@code batch-size} trabajos abandonados y devuelve cuántos recuperó. */
    public int recoverOnce() {
        List<Job> expired = jobs.findExpired(clock.instant(), batchSize);
        int recovered = 0;
        for (Job job : expired) {
            if (processor.recover(job)) {
                recovered++;
            }
        }
        if (recovered > 0) {
            log.info("Recuperados {} trabajo(s) abandonados", recovered);
        }
        return recovered;
    }
}
