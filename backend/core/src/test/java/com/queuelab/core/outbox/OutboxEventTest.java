package com.queuelab.core.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.queuelab.core.job.Job;
import com.queuelab.core.logging.LogContext;
import com.queuelab.core.messaging.JobMessageCodec;
import com.queuelab.core.tracing.JobTracing;

class OutboxEventTest {

    @Test
    void jobQueuedEventCarriesTheContractPayloadForThatJob() {
        Instant now = Instant.parse("2026-03-01T08:00:00Z");
        Job job = Job.queued(UUID.randomUUID(), "csv-import", now);

        OutboxEvent event = OutboxEvent.jobQueued(job, now);

        assertThat(event.jobId()).isEqualTo(job.id());
        assertThat(event.eventType()).isEqualTo("JOB_QUEUED");
        assertThat(event.isPublished()).isFalse();
        assertThat(event.attempts()).isZero();
        assertThat(JobMessageCodec.decode(JobMessageCodec.encodeJson(event.payload())).jobId()).isEqualTo(job.id());
    }

    @Test
    void deadLetteredEventCarriesJobIdAttemptsAndCauseOnly() {
        Instant now = Instant.parse("2026-03-01T08:00:00Z");
        Job failed = Job.queued(UUID.randomUUID(), "csv-import", now)
                .transitionTo(com.queuelab.core.job.JobStatus.RUNNING, now)
                .failed("El servicio externo no responde", now);

        OutboxEvent event = OutboxEvent.deadLettered(failed, now);

        assertThat(event.eventType()).isEqualTo("JOB_DEAD_LETTERED");
        assertThat(event.jobId()).isEqualTo(failed.id());
        assertThat(event.payload()).isEqualTo("{\"version\":1,\"jobId\":\"" + failed.id()
                + "\",\"attempts\":" + failed.attempts() + ",\"cause\":\"El servicio externo no responde\"}");
        // Sigue siendo un JobMessage válido para quien solo mire el id.
        assertThat(JobMessageCodec.decode(JobMessageCodec.encodeJson(event.payload())).jobId()).isEqualTo(failed.id());
    }

    @Test
    void newEventsInheritTheCorrelationIdOfTheCurrentContextOrGetTheirOwn() {
        Instant now = Instant.parse("2026-03-01T08:00:00Z");
        Job job = Job.queued(UUID.randomUUID(), "csv-import", now);

        try (LogContext.Scope ignored = LogContext.with("request-42", null)) {
            assertThat(OutboxEvent.jobQueued(job, now).correlationId()).isEqualTo("request-42");
        }
        String generated = OutboxEvent.jobQueued(job, now).correlationId();
        assertThat(LogContext.isValid(generated)).isTrue();
    }

    @Test
    void aCorrelationIdThatCouldForgeLogLinesIsRefused() {
        assertThatThrownBy(() -> new OutboxEvent(UUID.randomUUID(), UUID.randomUUID(), "JOB_QUEUED", "{}",
                Instant.now(), null, 0, "a\nb")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void newEventsRecordTheTraceOfTheActiveSpanAndHaveNoneWithoutOne() {
        Instant now = Instant.parse("2026-03-01T08:00:00Z");
        Job job = Job.queued(UUID.randomUUID(), "csv-import", now);
        String traceparent = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";

        assertThat(OutboxEvent.jobQueued(job, now).traceContext()).isNull();
        try (var ignored = io.opentelemetry.api.trace.Span.wrap(JobTracing.parse(traceparent)).makeCurrent()) {
            assertThat(OutboxEvent.jobQueued(job, now).traceContext()).isEqualTo(traceparent);
            assertThat(OutboxEvent.deadLettered(job, now).traceContext()).isEqualTo(traceparent);
        }
    }

    @Test
    void aTraceContextThatIsNotATraceparentIsRefused() {
        assertThatThrownBy(() -> new OutboxEvent(UUID.randomUUID(), UUID.randomUUID(), "JOB_QUEUED", "{}",
                Instant.now(), null, 0, null, "no-es-un-traceparent")).isInstanceOf(IllegalArgumentException.class);
    }
}
