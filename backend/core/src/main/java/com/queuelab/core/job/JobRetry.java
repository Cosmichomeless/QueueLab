package com.queuelab.core.job;

import java.time.Instant;
import java.util.UUID;

/**
 * Un reintento manual ya solicitado: lo que el trabajo había consumido antes de volver a {@code QUEUED}.
 *
 * @param attempts intentos de ejecución consumidos hasta ese momento
 * @param error    último error del trabajo al reintentarlo
 */
public record JobRetry(UUID id, UUID jobId, Instant requestedAt, int attempts, String error) {
}
