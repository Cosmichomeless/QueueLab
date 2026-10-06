package com.queuelab.core.tracing;

import java.time.Instant;
import java.util.regex.Pattern;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;

/**
 * Trazas de un trabajo a lo largo de sus tres saltos (petición HTTP, outbox/RabbitMQ y worker). Una sola traza
 * los une porque el contexto viaja en dos sitios que sobreviven a la espera: la columna
 * {@code outbox_events.trace_context} (de la petición al despachador) y la cabecera {@value #AMQP_HEADER} del
 * mensaje (del despachador al worker). Ambos usan el formato {@code traceparent} del W3C, así que cualquier
 * herramienta compatible lo entiende.
 *
 * <p>Los tiempos de espera (en el outbox, en la cola) son spans con marcas de inicio y fin explícitas: no hay
 * ningún hilo que los esté «viviendo», pero se ven en la traza como barras del ancho de la espera.
 */
public class JobTracing {

    /** Cabecera AMQP con el contexto de la traza (W3C {@code traceparent}). */
    public static final String AMQP_HEADER = "traceparent";

    /** Cabecera AMQP con el instante (ms desde epoch) en que el despachador entregó el mensaje al broker. */
    public static final String ENQUEUED_AT_HEADER = "x-queuelab-enqueued-at";

    public static final String INSTRUMENTATION_NAME = "queuelab";

    private static final Pattern TRACEPARENT =
            Pattern.compile("^00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$");

    private final Tracer tracer;

    public JobTracing(OpenTelemetry openTelemetry) {
        this.tracer = openTelemetry.getTracer(INSTRUMENTATION_NAME);
    }

    /** Instancia que no registra nada (para código que se ejecuta sin OpenTelemetry configurado). */
    public static JobTracing noop() {
        return new JobTracing(OpenTelemetry.noop());
    }

    /**
     * El {@code traceparent} del span activo en este hilo, o {@code null} si no hay ninguno. Lo llama quien
     * guarda un evento en el outbox, dentro de la petición (span HTTP) o del procesamiento (span del worker).
     */
    public static String currentTraceparent() {
        SpanContext current = Span.current().getSpanContext();
        return current.isValid() ? format(current) : null;
    }

    /** El {@code traceparent} de este contexto de span. */
    public static String format(SpanContext context) {
        return "00-" + context.getTraceId() + "-" + context.getSpanId() + "-" + context.getTraceFlags().asHex();
    }

    /**
     * Interpreta un {@code traceparent}. Devuelve {@code null} si falta o no es válido (formato, ids a cero):
     * un dato corrupto nunca debe impedir publicar ni ejecutar un trabajo, solo se pierde el enlace.
     */
    public static SpanContext parse(String traceparent) {
        if (traceparent == null) {
            return null;
        }
        var matcher = TRACEPARENT.matcher(traceparent);
        if (!matcher.matches()) {
            return null;
        }
        SpanContext context = SpanContext.createFromRemoteParent(matcher.group(1), matcher.group(2),
                TraceFlags.fromHex(matcher.group(3), 0), TraceState.getDefault());
        return context.isValid() ? context : null;
    }

    /**
     * Empieza un span hijo de {@code parent} (raíz si es {@code null}) con el instante de inicio dado. Quien lo
     * llama es responsable de cerrarlo con {@link Span#end()} o {@link Span#end(Instant)}.
     */
    public Span start(String name, SpanKind kind, SpanContext parent, Instant startedAt, Attributes attributes) {
        SpanBuilder builder = tracer.spanBuilder(name)
                .setSpanKind(kind)
                .setStartTimestamp(startedAt)
                .setAllAttributes(attributes);
        builder.setParent(parent == null ? Context.root() : Context.root().with(Span.wrap(parent)));
        return builder.startSpan();
    }

    /** Span hijo del que está activo en este hilo (raíz si no hay), que empieza ahora. */
    public Span startChild(String name, SpanKind kind, Attributes attributes) {
        return tracer.spanBuilder(name)
                .setSpanKind(kind)
                .setAllAttributes(attributes)
                .startSpan();
    }
}
