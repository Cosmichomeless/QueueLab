package com.queuelab.core.job;

import static org.assertj.core.api.Assertions.assertThat;
import static com.queuelab.core.job.JobStatus.*;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class JobStatusTest {

    private static final Map<JobStatus, Set<JobStatus>> VALID = Map.of(
            QUEUED, Set.of(RUNNING),
            RUNNING, Set.of(COMPLETED, FAILED, RETRYING),
            RETRYING, Set.of(RUNNING),
            COMPLETED, Set.of(),
            FAILED, Set.of(QUEUED));

    @Test
    void onlyDocumentedTransitionsAreAllowed() {
        for (JobStatus from : JobStatus.values()) {
            for (JobStatus to : JobStatus.values()) {
                assertThat(from.canTransitionTo(to))
                        .as("%s → %s", from, to)
                        .isEqualTo(VALID.get(from).contains(to));
            }
        }
    }

    @Test
    void completedAndFailedAreTerminal() {
        assertThat(JobStatus.values()).filteredOn(JobStatus::isTerminal)
                .containsExactlyInAnyOrder(COMPLETED, FAILED);
    }
}
