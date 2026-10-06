package com.queuelab.core.tracing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Scope;

class JobTracingTest {

    private static final String VALID = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";

    @Test
    void parsesAValidTraceparentAsARemoteContext() {
        SpanContext context = JobTracing.parse(VALID);

        assertThat(context).isNotNull();
        assertThat(context.getTraceId()).isEqualTo("0af7651916cd43dd8448eb211c80319c");
        assertThat(context.getSpanId()).isEqualTo("b7ad6b7169203331");
        assertThat(context.isSampled()).isTrue();
        assertThat(context.isRemote()).isTrue();
    }

    @Test
    void formatAndParseAreInverse() {
        SpanContext context = SpanContext.create("0af7651916cd43dd8448eb211c80319c", "b7ad6b7169203331",
                TraceFlags.getDefault(), TraceState.getDefault());

        String traceparent = JobTracing.format(context);

        assertThat(traceparent).isEqualTo("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-00");
        SpanContext parsed = JobTracing.parse(traceparent);
        assertThat(parsed.getTraceId()).isEqualTo(context.getTraceId());
        assertThat(parsed.getSpanId()).isEqualTo(context.getSpanId());
        assertThat(parsed.isSampled()).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "esto-no-es-un-traceparent",
            // Versión desconocida, mayúsculas, longitudes incorrectas, separadores de más o saltos de línea.
            "01-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01",
            "00-0AF7651916CD43DD8448EB211C80319C-b7ad6b7169203331-01",
            "00-0af7651916cd43dd8448eb211c80319-b7ad6b7169203331-01",
            "00-0af7651916cd43dd8448eb211c80319c-b7ad6b716920333-01",
            "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-1",
            VALID + "-extra",
            VALID + "\n",
            "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-zz",
            // Ids a cero: el formato es correcto pero el W3C los declara inválidos.
            "00-00000000000000000000000000000000-b7ad6b7169203331-01",
            "00-0af7651916cd43dd8448eb211c80319c-0000000000000000-01"
    })
    void invalidTraceparentsYieldNullInsteadOfFailing(String traceparent) {
        assertThat(JobTracing.parse(traceparent)).isNull();
    }

    @Test
    void thereIsNoCurrentTraceparentOutsideASpan() {
        assertThat(JobTracing.currentTraceparent()).isNull();
    }

    @Test
    void theCurrentTraceparentIsThatOfTheActiveSpan() {
        Span span = Span.wrap(JobTracing.parse(VALID));

        try (Scope ignored = span.makeCurrent()) {
            assertThat(JobTracing.currentTraceparent()).isEqualTo(VALID);
        }
        assertThat(JobTracing.currentTraceparent()).isNull();
    }

    @Test
    void theNoopInstanceNeverFailsAndProducesNoValidContext() {
        JobTracing tracing = JobTracing.noop();

        Span span = tracing.start("x", SpanKind.INTERNAL, JobTracing.parse(VALID), java.time.Instant.now(),
                Attributes.empty());
        span.end();
        Span child = tracing.startChild("y", SpanKind.INTERNAL, Attributes.empty());
        child.end();

        assertThat(child.getSpanContext().isValid()).isFalse();
    }
}
