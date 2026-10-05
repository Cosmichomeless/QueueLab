package com.queuelab.core.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class JobTest {

    private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");
    private static final Instant T1 = T0.plusSeconds(10);
    private static final Instant T2 = T0.plusSeconds(20);
    private static final Instant T3 = T0.plusSeconds(30);

    private final UUID id = UUID.randomUUID();

    @Test
    void newJobIsQueuedWithoutStartOrFinish() {
        Job job = Job.queued(id, "csv-import", T0);

        assertThat(job.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(job.createdAt()).isEqualTo(T0);
        assertThat(job.updatedAt()).isEqualTo(T0);
        assertThat(job.startedAt()).isNull();
        assertThat(job.finishedAt()).isNull();
    }

    @Test
    void happyPathSetsTimestamps() {
        Job job = Job.queued(id, "csv-import", T0)
                .transitionTo(JobStatus.RUNNING, T1)
                .transitionTo(JobStatus.COMPLETED, T2);

        assertThat(job.status()).isEqualTo(JobStatus.COMPLETED);
        assertThat(job.createdAt()).isEqualTo(T0);
        assertThat(job.startedAt()).isEqualTo(T1);
        assertThat(job.finishedAt()).isEqualTo(T2);
        assertThat(job.updatedAt()).isEqualTo(T2);
    }

    @Test
    void retryKeepsFirstStartAndIsNotFinished() {
        Job job = Job.queued(id, "csv-import", T0)
                .transitionTo(JobStatus.RUNNING, T1)
                .transitionTo(JobStatus.RETRYING, T2)
                .transitionTo(JobStatus.RUNNING, T3);

        assertThat(job.startedAt()).isEqualTo(T1);
        assertThat(job.finishedAt()).isNull();
        assertThat(job.updatedAt()).isEqualTo(T3);
    }

    @Test
    void failedIsTerminalAndSetsFinishedAt() {
        Job job = Job.queued(id, "csv-import", T0)
                .transitionTo(JobStatus.RUNNING, T1)
                .transitionTo(JobStatus.FAILED, T2);

        assertThat(job.finishedAt()).isEqualTo(T2);
    }

    @Test
    void invalidTransitionIsRejectedAndLeavesJobUnchanged() {
        Job queued = Job.queued(id, "csv-import", T0);

        assertThatThrownBy(() -> queued.transitionTo(JobStatus.COMPLETED, T1))
                .isInstanceOf(InvalidJobTransitionException.class)
                .hasMessageContaining("QUEUED")
                .hasMessageContaining("COMPLETED");
        assertThat(queued.status()).isEqualTo(JobStatus.QUEUED);
    }

    @Test
    void terminalJobCannotMove() {
        Job done = Job.queued(id, "csv-import", T0)
                .transitionTo(JobStatus.RUNNING, T1)
                .transitionTo(JobStatus.COMPLETED, T2);

        for (JobStatus target : JobStatus.values()) {
            assertThatThrownBy(() -> done.transitionTo(target, T3))
                    .isInstanceOf(InvalidJobTransitionException.class);
        }
    }

    @Test
    void typeMustNotBeBlank() {
        assertThatThrownBy(() -> Job.queued(id, " ", T0)).isInstanceOf(IllegalArgumentException.class);
    }
}
