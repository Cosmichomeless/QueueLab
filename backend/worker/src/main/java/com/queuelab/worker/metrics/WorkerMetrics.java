package com.queuelab.worker.metrics;

import java.time.Duration;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Métricas del worker. Todas las etiquetas tienen un conjunto cerrado de valores: {@code outcome}, {@code cause}
 * y {@code reason} son constantes de este fichero y {@code type} se sanea ({@link #typeTag}). Nunca se usan el
 * id del trabajo ni el de correlación como etiqueta: cada valor nuevo crearía una serie nueva y Prometheus
 * acabaría ahogado. Para seguir un trabajo concreto están los logs y las trazas.
 */
@Component
public class WorkerMetrics {

    public static final String OUTCOME_COMPLETED = "completed";
    public static final String OUTCOME_FAILED = "failed";
    public static final String OUTCOME_RETRY = "retry";
    public static final String OUTCOME_DEAD_LETTER = "dead_letter";

    public static final String CAUSE_TRANSIENT = "transient";
    public static final String CAUSE_LEASE_EXPIRED = "lease_expired";

    public static final String REASON_MALFORMED = "malformed";
    public static final String REASON_UNKNOWN_JOB = "unknown_job";

    /** Tipos con forma de identificador corto; el resto se agrupa para no dejar crecer la cardinalidad. */
    private static final Pattern TYPE_FORMAT = Pattern.compile("[a-z0-9][a-z0-9._-]{0,31}");
    static final String OTHER_TYPE = "other";

    /** Cubos de latencia: desde un trabajo trivial (10 ms) hasta el límite razonable de una ejecución. */
    private static final Duration[] SLOS = {
            Duration.ofMillis(10), Duration.ofMillis(50), Duration.ofMillis(250), Duration.ofSeconds(1),
            Duration.ofSeconds(5), Duration.ofSeconds(15), Duration.ofSeconds(60), Duration.ofMinutes(5),
            Duration.ofMinutes(30)
    };

    private final MeterRegistry registry;

    public WorkerMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** Cuánto duró un intento, desde que se reclamó hasta que se guardó su resultado. */
    public void executionFinished(String type, String outcome, Duration elapsed) {
        Timer.builder("queuelab.job.execution")
                .description("Duración de un intento de ejecución, por tipo y resultado")
                .tag("type", typeTag(type))
                .tag("outcome", outcome)
                .serviceLevelObjectives(SLOS)
                .register(registry)
                .record(elapsed);
    }

    /** Espera en cola: de la creación del trabajo a su primer reclamo (los reintentos no cuentan: esperan a propósito). */
    public void waited(String type, Duration waited) {
        Timer.builder("queuelab.job.wait")
                .description("Tiempo entre crear el trabajo y empezar su primer intento")
                .tag("type", typeTag(type))
                .serviceLevelObjectives(SLOS)
                .register(registry)
                .record(waited.isNegative() ? Duration.ZERO : waited);
    }

    /** Un reintento programado (ya guardado), por {@code transient} o {@code lease_expired}. */
    public void retryScheduled(String type, String cause) {
        counter("queuelab.job.retries", "Reintentos programados", type, "cause", cause).increment();
    }

    /** Un trabajo que agotó sus intentos y pasó a la dead-letter queue (ya guardado). */
    public void deadLettered(String type, String cause) {
        counter("queuelab.job.dead_letters", "Trabajos enviados a la dead-letter queue", type, "cause", cause)
                .increment();
    }

    /** Un mensaje que el consumidor rechazó sin reencolar. */
    public void messageRejected(String reason) {
        Counter.builder("queuelab.messages.rejected")
                .description("Mensajes rechazados hacia la DLQ por el consumidor")
                .tag("reason", reason)
                .register(registry)
                .increment();
    }

    private Counter counter(String name, String description, String type, String tagName, String tagValue) {
        return Counter.builder(name)
                .description(description)
                .tag("type", typeTag(type))
                .tag(tagName, tagValue)
                .register(registry);
    }

    static String typeTag(String type) {
        return type != null && TYPE_FORMAT.matcher(type).matches() ? type : OTHER_TYPE;
    }
}
