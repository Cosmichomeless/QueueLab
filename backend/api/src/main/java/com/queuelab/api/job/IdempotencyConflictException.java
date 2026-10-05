package com.queuelab.api.job;

/** La clave de idempotencia ya se usó con una carga distinta; se responde 409. */
public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException() {
        super("La clave de idempotencia ya se usó con una petición distinta. "
                + "Usa una clave nueva para enviar una carga diferente.");
    }
}
