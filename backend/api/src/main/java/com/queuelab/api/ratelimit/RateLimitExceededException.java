package com.queuelab.api.ratelimit;

/** El cliente agotó su cuota de envíos de la ventana: el envío se rechaza sin guardar nada. */
public class RateLimitExceededException extends RuntimeException {

    private final transient RateLimitDecision decision;
    private final long windowSeconds;

    public RateLimitExceededException(RateLimitDecision decision, long windowSeconds) {
        super("Has superado el límite de " + decision.limit() + " envíos cada " + windowSeconds
                + " s. Inténtalo de nuevo en " + decision.resetSeconds() + " s.");
        this.decision = decision;
        this.windowSeconds = windowSeconds;
    }

    public RateLimitDecision decision() {
        return decision;
    }

    public long windowSeconds() {
        return windowSeconds;
    }
}
