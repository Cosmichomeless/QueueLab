package com.queuelab.api.queue;

/**
 * Saturación de la cola.
 *
 * @param pending           trabajos esperando worker; se cuenta solo hasta {@code maxPending}, así que nunca lo supera
 * @param maxPending        umbral configurado
 * @param saturated         {@code pending >= maxPending}: los envíos nuevos se rechazan
 * @param retryAfterSeconds espera sugerida a los clientes rechazados
 */
public record QueueStatus(long pending, int maxPending, boolean saturated, long retryAfterSeconds) {
}
