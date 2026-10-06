package com.queuelab.worker.job;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.queuelab.core.job.JobRepository;
import com.queuelab.core.job.JobRepository.StoredReference;
import com.queuelab.core.storage.FileStorage;
import com.queuelab.core.storage.StorageArea;
import com.queuelab.core.storage.StorageException;

/**
 * Limpieza de ficheros según la política de retención (ver {@code docs/csv-workload.md}):
 *
 * <ul>
 *   <li>la <b>entrada</b> de un trabajo {@code COMPLETED} se borra pasado {@code queuelab.cleanup.completed-input-retention};</li>
 *   <li>la de uno {@code FAILED}, pasado {@code queuelab.cleanup.failed-input-retention} (puede reintentarse a mano);</li>
 *   <li>el <b>resultado</b> de un {@code COMPLETED}, pasado {@code queuelab.cleanup.result-retention};</li>
 *   <li>los <b>temporales</b> de escrituras interrumpidas y los <b>huérfanos</b> (ficheros a los que ningún trabajo
 *       apunta, p. ej. si el proceso cayó entre guardar el fichero y crear el trabajo), pasado
 *       {@code queuelab.cleanup.temporary-retention}, margen que evita borrar una subida en curso.</li>
 * </ul>
 *
 * <p>Los trabajos pendientes, en curso o en reintento no se tocan nunca. Para cada fichero primero se suelta la
 * referencia en la base de datos (con la condición de estado terminal, de modo que un reintento manual
 * concurrente gana) y solo después se borra; si el borrado falla, se restaura la referencia y se reintenta en la
 * siguiente pasada.
 */
@Component
public class StorageCleaner {

    private static final Logger log = LoggerFactory.getLogger(StorageCleaner.class);

    private final JobRepository jobs;
    private final FileStorage storage;
    private final Clock clock;
    private final Duration completedInputRetention;
    private final Duration failedInputRetention;
    private final Duration resultRetention;
    private final Duration temporaryRetention;
    private final int batchSize;

    StorageCleaner(JobRepository jobs, FileStorage storage, Clock clock,
            @Value("${queuelab.cleanup.completed-input-retention:1h}") Duration completedInputRetention,
            @Value("${queuelab.cleanup.failed-input-retention:7d}") Duration failedInputRetention,
            @Value("${queuelab.cleanup.result-retention:30d}") Duration resultRetention,
            @Value("${queuelab.cleanup.temporary-retention:1h}") Duration temporaryRetention,
            @Value("${queuelab.cleanup.batch-size:100}") int batchSize) {
        this.jobs = jobs;
        this.storage = storage;
        this.clock = clock;
        this.completedInputRetention = completedInputRetention;
        this.failedInputRetention = failedInputRetention;
        this.resultRetention = resultRetention;
        this.temporaryRetention = temporaryRetention;
        this.batchSize = batchSize;
    }

    /** Una pasada de limpieza; devuelve cuántos ficheros borró. */
    public int cleanOnce() {
        Instant now = clock.instant();
        int removed = 0;
        removed += release(jobs.findReleasableInputs(now.minus(completedInputRetention),
                now.minus(failedInputRetention), batchSize), true);
        removed += release(jobs.findReleasableResults(now.minus(resultRetention), batchSize), false);
        removed += storage.purgeTemporaries(now.minus(temporaryRetention));
        removed += removeOrphans(now.minus(temporaryRetention));
        if (removed > 0) {
            log.info("Limpieza de almacenamiento: {} fichero(s) borrados", removed);
        }
        return removed;
    }

    private int removeOrphans(Instant olderThan) {
        int removed = 0;
        for (StorageArea area : StorageArea.values()) {
            for (String reference : storage.listReferences(area, olderThan)) {
                if (!jobs.isReferenced(reference)) {
                    try {
                        if (storage.delete(reference)) {
                            removed++;
                        }
                    } catch (StorageException e) {
                        log.warn("No se pudo borrar el huérfano {}; se reintenta en la próxima pasada", reference, e);
                    }
                }
            }
        }
        return removed;
    }

    private int release(List<StoredReference> candidates, boolean input) {
        int removed = 0;
        for (StoredReference candidate : candidates) {
            boolean released = input
                    ? jobs.clearInputRef(candidate.jobId(), candidate.reference())
                    : jobs.clearResultRef(candidate.jobId(), candidate.reference());
            if (!released) {
                continue; // cambió de estado entre la lectura y ahora (p. ej. reintento manual)
            }
            try {
                storage.delete(candidate.reference());
                removed++;
            } catch (StorageException e) {
                restore(candidate, input);
                log.warn("No se pudo borrar {}; se reintenta en la próxima pasada", candidate.reference(), e);
            }
        }
        return removed;
    }

    private void restore(StoredReference candidate, boolean input) {
        if (input) {
            jobs.attachInput(candidate.jobId(), candidate.reference());
        } else {
            jobs.attachResult(candidate.jobId(), candidate.reference());
        }
    }
}
