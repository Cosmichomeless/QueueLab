package com.queuelab.api.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Lanza el despachador periódicamente. Se puede apagar con {@code queuelab.outbox.dispatch.enabled=false}. */
@Component
@ConditionalOnProperty(name = "queuelab.outbox.dispatch.enabled", havingValue = "true", matchIfMissing = true)
class OutboxDispatchScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatchScheduler.class);

    private final OutboxDispatcher dispatcher;

    OutboxDispatchScheduler(OutboxDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelayString = "${queuelab.outbox.dispatch.interval:1s}")
    void run() {
        try {
            int published = dispatcher.dispatchPending();
            if (published > 0) {
                log.debug("Outbox: {} evento(s) publicados", published);
            }
        } catch (RuntimeException e) {
            log.warn("Fallo despachando el outbox; se reintenta en la próxima pasada", e);
        }
    }
}
