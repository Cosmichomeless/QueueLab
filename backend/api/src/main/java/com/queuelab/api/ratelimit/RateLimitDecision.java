package com.queuelab.api.ratelimit;

/**
 * Resultado de contar un envío en la ventana de un cliente.
 *
 * @param allowed       si el envío cabe en la cuota
 * @param limit         envíos permitidos por ventana
 * @param used          envíos contados en la ventana, incluido este
 * @param resetSeconds  segundos que faltan para que la ventana se reinicie (mínimo 1)
 */
public record RateLimitDecision(boolean allowed, long limit, long used, long resetSeconds) {

    public long remaining() {
        return Math.max(0, limit - used);
    }
}
