package com.queuelab.core.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.queuelab.core.job.Job;
import com.queuelab.core.messaging.JobMessageCodec;

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
}
