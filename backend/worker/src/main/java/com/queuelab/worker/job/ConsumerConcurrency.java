package com.queuelab.worker.job;

/**
 * Cuántos mensajes procesa a la vez un worker.
 *
 * @param consumers consumidores simultáneos (hilos): el máximo de trabajos que este proceso ejecuta a la vez
 * @param prefetch  mensajes sin confirmar que RabbitMQ entrega a cada consumidor; con {@code 1} un worker nunca
 *                  acapara mensajes que otro, más libre, podría procesar
 */
record ConsumerConcurrency(int consumers, int prefetch) {

    static final int MAX_CONSUMERS = 64;

    ConsumerConcurrency {
        if (consumers < 1 || consumers > MAX_CONSUMERS) {
            throw new IllegalArgumentException("queuelab.worker.concurrency debe estar entre 1 y " + MAX_CONSUMERS
                    + " (valor: " + consumers + ")");
        }
        if (prefetch < 1) {
            throw new IllegalArgumentException("queuelab.worker.prefetch debe ser al menos 1 (valor: " + prefetch + ")");
        }
    }
}
