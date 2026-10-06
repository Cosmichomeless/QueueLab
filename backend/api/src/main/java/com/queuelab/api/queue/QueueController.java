package com.queuelab.api.queue;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/queue")
class QueueController {

    private final QueueBackpressure backpressure;

    QueueController(QueueBackpressure backpressure) {
        this.backpressure = backpressure;
    }

    /** Trabajos pendientes frente al umbral de contrapresión: lo que miden los clientes y los benchmarks. */
    @GetMapping
    QueueStatus status() {
        return backpressure.status();
    }
}
