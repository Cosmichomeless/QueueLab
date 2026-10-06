package com.queuelab.api.metrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Refresca la foto de {@link QueueMetrics}. Se puede apagar con {@code queuelab.metrics.refresh.enabled=false}. */
@Component
@ConditionalOnProperty(name = "queuelab.metrics.refresh.enabled", havingValue = "true", matchIfMissing = true)
class QueueMetricsScheduler {

    private static final Logger log = LoggerFactory.getLogger(QueueMetricsScheduler.class);

    private final QueueMetrics metrics;

    QueueMetricsScheduler(QueueMetrics metrics) {
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${queuelab.metrics.refresh.interval:15s}")
    void run() {
        try {
            metrics.refresh();
        } catch (RuntimeException e) {
            log.warn("Fallo refrescando las métricas de cola; se reintenta en la próxima pasada", e);
        }
    }
}
