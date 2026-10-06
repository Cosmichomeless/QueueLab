package com.queuelab.core.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;

class LogContextTest {

    @AfterEach
    void clean() {
        MDC.clear();
    }

    @Test
    void generatedIdsAreValidAndDistinct() {
        String first = LogContext.newCorrelationId();

        assertThat(LogContext.isValid(first)).isTrue();
        assertThat(first).isNotEqualTo(LogContext.newCorrelationId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "A1._-z", "123e4567-e89b-12d3-a456-426614174000"})
    void acceptsPlainTokens(String value) {
        assertThat(LogContext.isValid(value)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "con espacios", "a\nb", "a\r\nX-Injected: 1", "{\"x\":1}", "ñandú", "<script>"})
    void rejectsAnythingThatCouldForgeALogLine(String value) {
        assertThat(LogContext.isValid(value)).isFalse();
    }

    @Test
    void rejectsNullAndTooLongValues() {
        assertThat(LogContext.isValid(null)).isFalse();
        assertThat(LogContext.isValid("a".repeat(LogContext.MAX_LENGTH))).isTrue();
        assertThat(LogContext.isValid("a".repeat(LogContext.MAX_LENGTH + 1))).isFalse();
    }

    @Test
    void scopePutsValuesInTheMdcAndRestoresThePreviousOnesOnClose() {
        UUID outer = UUID.randomUUID();
        UUID inner = UUID.randomUUID();
        try (LogContext.Scope ignored = LogContext.with("outer-id", outer)) {
            try (LogContext.Scope nested = LogContext.with("inner-id", inner)) {
                assertThat(MDC.get(LogContext.CORRELATION_ID)).isEqualTo("inner-id");
                assertThat(MDC.get(LogContext.JOB_ID)).isEqualTo(inner.toString());
            }
            assertThat(LogContext.correlationId()).isEqualTo("outer-id");
            assertThat(MDC.get(LogContext.JOB_ID)).isEqualTo(outer.toString());
        }
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void nullValuesLeaveTheCurrentContextUntouched() {
        UUID jobId = UUID.randomUUID();
        try (LogContext.Scope ignored = LogContext.with("keep-me", null)) {
            try (LogContext.Scope nested = LogContext.with(null, jobId)) {
                assertThat(LogContext.correlationId()).isEqualTo("keep-me");
                assertThat(MDC.get(LogContext.JOB_ID)).isEqualTo(jobId.toString());
            }
            assertThat(MDC.get(LogContext.JOB_ID)).isNull();
            assertThat(LogContext.correlationId()).isEqualTo("keep-me");
        }
    }
}
