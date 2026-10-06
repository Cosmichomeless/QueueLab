package com.queuelab.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;

import com.queuelab.api.ratelimit.RateLimitDecision;
import com.queuelab.api.ratelimit.SubmissionRateLimiter;

/**
 * El limitador contra un Redis real: ventana fija, reinicio al expirar, cuota compartida entre instancias
 * (dos limitadores con conexiones independientes, como dos procesos de la API) y comportamiento sin Redis.
 */
class SubmissionRateLimiterTest {

    static GenericContainer<?> redis;
    static LettuceConnectionFactory factoryA;
    static LettuceConnectionFactory factoryB;

    @BeforeAll
    static void start() {
        redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
        redis.start();
        factoryA = connect(redis.getHost(), redis.getMappedPort(6379));
        factoryB = connect(redis.getHost(), redis.getMappedPort(6379));
    }

    @AfterAll
    static void stop() {
        factoryA.destroy();
        factoryB.destroy();
        redis.stop();
    }

    private static LettuceConnectionFactory connect(String host, int port) {
        var factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port));
        factory.afterPropertiesSet();
        return factory;
    }

    private static SubmissionRateLimiter limiter(LettuceConnectionFactory factory, int max, Duration window) {
        var template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        return new SubmissionRateLimiter(template, max, window);
    }

    private static String client() {
        return "unit-" + UUID.randomUUID();
    }

    @Test
    void countsRequestsInTheWindowAndRejectsTheExcess() {
        var limiter = limiter(factoryA, 2, Duration.ofSeconds(30));
        String client = client();

        RateLimitDecision first = limiter.tryAcquire(client);
        assertThat(first.allowed()).isTrue();
        assertThat(first.used()).isEqualTo(1);
        assertThat(first.remaining()).isEqualTo(1);
        assertThat(limiter.tryAcquire(client).allowed()).isTrue();

        RateLimitDecision third = limiter.tryAcquire(client);
        assertThat(third.allowed()).isFalse();
        assertThat(third.remaining()).isZero();
        assertThat(third.resetSeconds()).isBetween(1L, 30L);
    }

    @Test
    void theQuotaIsSharedBetweenInstances() {
        var instanceA = limiter(factoryA, 4, Duration.ofSeconds(30));
        var instanceB = limiter(factoryB, 4, Duration.ofSeconds(30));
        String client = client();

        // Reparto alterno: ninguna instancia llega sola a 4, pero entre las dos se agota la cuota.
        assertThat(instanceA.tryAcquire(client).allowed()).isTrue();
        assertThat(instanceB.tryAcquire(client).allowed()).isTrue();
        assertThat(instanceA.tryAcquire(client).allowed()).isTrue();
        assertThat(instanceB.tryAcquire(client).allowed()).isTrue();
        assertThat(instanceA.tryAcquire(client).allowed()).isFalse();
        assertThat(instanceB.tryAcquire(client).allowed()).isFalse();
    }

    @Test
    void concurrentRequestsNeverExceedTheQuota() throws Exception {
        var instanceA = limiter(factoryA, 25, Duration.ofSeconds(30));
        var instanceB = limiter(factoryB, 25, Duration.ofSeconds(30));
        String client = client();

        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < 100; i++) {
                var chosen = i % 2 == 0 ? instanceA : instanceB;
                tasks.add(executor.submit(() -> chosen.tryAcquire(client).allowed()));
            }
            long allowed = 0;
            for (var task : tasks) {
                if (task.get()) {
                    allowed++;
                }
            }
            assertThat(allowed).isEqualTo(25);
        }
    }

    @Test
    void theWindowResetsWhenItExpires() throws Exception {
        var limiter = limiter(factoryA, 1, Duration.ofSeconds(1));
        String client = client();

        assertThat(limiter.tryAcquire(client).allowed()).isTrue();
        assertThat(limiter.tryAcquire(client).allowed()).isFalse();

        Thread.sleep(1200);

        assertThat(limiter.tryAcquire(client).allowed()).isTrue();
    }

    @Test
    void withoutRedisRequestsAreAllowed() {
        var unreachable = connect("localhost", 1);
        try {
            var template = new StringRedisTemplate(unreachable);
            template.afterPropertiesSet();
            var limiter = new SubmissionRateLimiter(template, 1, Duration.ofSeconds(30));

            assertThat(limiter.tryAcquire(client()).allowed()).isTrue();
            assertThat(limiter.tryAcquire(client()).allowed()).isTrue();
        } finally {
            unreachable.destroy();
        }
    }

    @Test
    void theQuotaMustBePositive() {
        var template = new StringRedisTemplate(factoryA);
        assertThatThrownBy(() -> new SubmissionRateLimiter(template, 0, Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("max-requests");
        assertThatThrownBy(() -> new SubmissionRateLimiter(template, 5, Duration.ofMillis(500)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("window");
    }
}
