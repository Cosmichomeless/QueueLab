package com.queuelab.worker.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Lanza la limpieza periódicamente. Se apaga con {@code queuelab.cleanup.enabled=false}. */
@Component
@ConditionalOnProperty(name = "queuelab.cleanup.enabled", havingValue = "true", matchIfMissing = true)
class CleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(CleanupScheduler.class);

    private final StorageCleaner cleaner;

    CleanupScheduler(StorageCleaner cleaner) {
        this.cleaner = cleaner;
    }

    @Scheduled(fixedDelayString = "${queuelab.cleanup.interval:10m}", initialDelayString = "${queuelab.cleanup.initial-delay:1m}")
    void run() {
        try {
            cleaner.cleanOnce();
        } catch (RuntimeException e) {
            log.warn("Fallo en la limpieza de almacenamiento; se reintenta en la próxima pasada", e);
        }
    }
}
