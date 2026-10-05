package com.queuelab.core.job;

/** Un trabajo creado con clave de idempotencia, junto con la huella de la petición que lo originó. */
public record StoredSubmission(Job job, String fingerprint) {
}
