package com.queuelab.worker.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Lanza el recuperador periódicamente. Se apaga con {@code queuelab.worker.recovery.enabled=false}. */
@Component
@ConditionalOnProperty(name = "queuelab.worker.recovery.enabled", havingValue = "true", matchIfMissing = true)
class RecoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(RecoveryScheduler.class);

    private final AbandonedJobRecoverer recoverer;

    RecoveryScheduler(AbandonedJobRecoverer recoverer) {
        this.recoverer = recoverer;
    }

    @Scheduled(fixedDelayString = "${queuelab.worker.recovery.interval:30s}")
    void run() {
        try {
            recoverer.recoverOnce();
        } catch (RuntimeException e) {
            log.warn("Fallo recuperando trabajos abandonados; se reintenta en la próxima pasada", e);
        }
    }
}
