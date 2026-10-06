package com.queuelab.api.ratelimit;

import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

/**
 * Cuota de envíos por cliente en una ventana fija, guardada en Redis para que todas las instancias de la API
 * compartan el mismo contador.
 *
 * <p>El contador es una clave por cliente que expira sola al terminar la ventana (el tiempo lo lleva Redis, no
 * los relojes de las instancias). Incrementar y fijar la expiración ocurren en un único script Lua, así que son
 * atómicos aunque varias instancias cuenten a la vez y no puede quedar una clave sin expiración.
 *
 * <p>Si Redis no responde se deja pasar la petición (<em>fail-open</em>) y se avisa en el log: la API ya sigue
 * aceptando trabajos con RabbitMQ caído, y la cuota es una protección, no una condición de corrección.
 */
@Service
@ConditionalOnProperty(name = "queuelab.rate-limit.enabled", havingValue = "true", matchIfMissing = true)
public class SubmissionRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(SubmissionRateLimiter.class);

    static final String KEY_PREFIX = "queuelab:ratelimit:submit:";

    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> COUNT = new DefaultRedisScript<>("""
            local used = redis.call('INCR', KEYS[1])
            local ttl = redis.call('PTTL', KEYS[1])
            if used == 1 or ttl < 0 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
              ttl = tonumber(ARGV[1])
            end
            return {used, ttl}
            """, List.class);

    private final StringRedisTemplate redis;
    private final int maxRequests;
    private final Duration window;

    public SubmissionRateLimiter(StringRedisTemplate redis,
            @Value("${queuelab.rate-limit.max-requests:60}") int maxRequests,
            @Value("${queuelab.rate-limit.window:1m}") Duration window) {
        if (maxRequests < 1) {
            throw new IllegalArgumentException("queuelab.rate-limit.max-requests debe ser al menos 1: " + maxRequests);
        }
        if (window.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new IllegalArgumentException("queuelab.rate-limit.window debe ser al menos 1 s: " + window);
        }
        this.redis = redis;
        this.maxRequests = maxRequests;
        this.window = window;
    }

    public int maxRequests() {
        return maxRequests;
    }

    public Duration window() {
        return window;
    }

    /** Cuenta un envío del cliente y dice si cabe en su cuota. */
    @SuppressWarnings("unchecked")
    public RateLimitDecision tryAcquire(String client) {
        try {
            List<Long> result = redis.execute(COUNT, List.of(KEY_PREFIX + client), String.valueOf(window.toMillis()));
            long used = result.get(0);
            long resetSeconds = Math.max(1, (result.get(1) + 999) / 1000);
            return new RateLimitDecision(used <= maxRequests, maxRequests, used, resetSeconds);
        } catch (RuntimeException e) {
            log.warn("Redis no responde: se omite el límite de envíos para {} ({})", client, e.toString());
            return new RateLimitDecision(true, maxRequests, 0, window.toSeconds());
        }
    }
}
