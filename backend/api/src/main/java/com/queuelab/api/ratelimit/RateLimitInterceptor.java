package com.queuelab.api.ratelimit;

import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Aplica la cuota de envíos a {@code POST /api/v1/jobs} y {@code POST /api/v1/jobs/csv} (se registran solo esas
 * rutas). Va antes del controlador, así que un envío rechazado no valida, guarda ni encola nada. Al lanzar la
 * excepción desde aquí el 429 sale por el mismo manejador de errores que el resto de la API.
 *
 * <p>El cliente es la IP remota de la conexión. Detrás de un proxy hay que activar
 * {@code server.forward-headers-strategy} para que sea la del cliente real y no la del proxy.
 */
class RateLimitInterceptor implements HandlerInterceptor {

    private final SubmissionRateLimiter limiter;

    RateLimitInterceptor(SubmissionRateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!HttpMethod.POST.matches(request.getMethod())) {
            return true;
        }
        RateLimitDecision decision = limiter.tryAcquire(request.getRemoteAddr());
        response.setHeader("RateLimit-Limit", String.valueOf(decision.limit()));
        response.setHeader("RateLimit-Remaining", String.valueOf(decision.remaining()));
        response.setHeader("RateLimit-Reset", String.valueOf(decision.resetSeconds()));
        if (!decision.allowed()) {
            throw new RateLimitExceededException(decision, limiter.window().toSeconds());
        }
        return true;
    }
}
