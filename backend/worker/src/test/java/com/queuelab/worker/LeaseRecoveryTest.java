package com.queuelab.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.queuelab.core.job.Job;
import com.queuelab.core.job.JobRepository;
import com.queuelab.core.job.JobStatus;
import com.queuelab.core.messaging.JobMessage;
import com.queuelab.worker.job.AbandonedJobRecoverer;
import com.queuelab.worker.job.JobExecutor;
import com.queuelab.worker.job.JobProcessor;
import com.queuelab.worker.support.ContainersTestConfiguration;

/** Recuperación de trabajos RUNNING cuyo worker cayó: lease de 900 ms, latido cada 300 ms. */
@SpringBootTest(properties = {
        "queuelab.worker.lease.duration=900ms",
        "queuelab.worker.retry.max-attempts=3",
        "queuelab.worker.retry.initial-delay=2s",
        "queuelab.worker.retry.multiplier=2.0",
        "queuelab.worker.retry.max-delay=1m"})
@Import(ContainersTestConfiguration.class)
class LeaseRecoveryTest {

    @Autowired
    AbandonedJobRecoverer recoverer;

    @Autowired
    JobProcessor processor;

    @Autowired
    JobRepository jobs;

    @Autowired
    JdbcClient jdbc;

    @MockitoSpyBean
    JobExecutor executor;

    @BeforeEach
    void clean() {
        org.mockito.Mockito.reset(executor);
        jdbc.sql("DELETE FROM jobs").update();
    }

    /** Un trabajo que un worker reclamó (intento {@code attempts}) y cuyo lease vence en {@code leaseEnd}. */
    private Job runningWithLease(Instant leaseEnd, int priorAttempts) {
        Job job = Job.queued(UUID.randomUUID(), "csv-import", Instant.now().minusSeconds(600));
        jobs.insert(job);
        Job current = job;
        for (int i = 0; i < priorAttempts; i++) {
            current = jobs.claim(job.id(), Instant.now().minusSeconds(500), leaseEnd).orElseThrow();
            if (i < priorAttempts - 1) {
                jdbc.sql("UPDATE jobs SET status = 'RETRYING', lease_expires_at = NULL WHERE id = :id")
                        .param("id", job.id()).update();
            }
        }
        return current;
    }

    private Job stored(Job job) {
        return jobs.findById(job.id()).orElseThrow();
    }

    private List<String> events(Job job) {
        return jdbc.sql("SELECT event_type FROM outbox_events WHERE job_id = :id ORDER BY created_at, id")
                .param("id", job.id()).query(String.class).list();
    }

    private Instant leaseOf(Job job) {
        return jdbc.sql("SELECT lease_expires_at FROM jobs WHERE id = :id").param("id", job.id())
                .query((rs, i) -> Optional.ofNullable(rs.getTimestamp(1)).map(java.sql.Timestamp::toInstant))
                .single().orElse(null);
    }

    @Test
    void expiredLeaseWithAttemptsLeftIsRescheduledAsRetrying() {
        Job abandoned = runningWithLease(Instant.now().minusSeconds(5), 1);

        assertThat(recoverer.recoverOnce()).isEqualTo(1);

        Job recovered = stored(abandoned);
        assertThat(recovered.status()).isEqualTo(JobStatus.RETRYING);
        assertThat(recovered.attempts()).isEqualTo(1);
        assertThat(recovered.error()).isEqualTo(JobProcessor.ABANDONED_ERROR);
        assertThat(leaseOf(abandoned)).isNull();
        assertThat(events(abandoned)).containsExactly("JOB_QUEUED");
        // El reintento se republica con la espera de la política (2 s tras el primer intento).
        assertThat(jdbc.sql("SELECT available_at > now() + interval '1 second' FROM outbox_events WHERE job_id = :id")
                .param("id", abandoned.id()).query(Boolean.class).single()).isTrue();
    }

    @Test
    void expiredLeaseWithoutAttemptsLeftFailsAndGoesToTheDeadLetterQueue() {
        Job abandoned = runningWithLease(Instant.now().minusSeconds(5), 3);
        assertThat(abandoned.attempts()).isEqualTo(3);

        assertThat(recoverer.recoverOnce()).isEqualTo(1);

        Job failed = stored(abandoned);
        assertThat(failed.status()).isEqualTo(JobStatus.FAILED);
        assertThat(failed.error()).isEqualTo(JobProcessor.ABANDONED_ERROR);
        assertThat(failed.finishedAt()).isNotNull();
        assertThat(events(abandoned)).containsExactly("JOB_DEAD_LETTERED");
    }

    @Test
    void jobWithAnActiveLeaseIsNotRecovered() {
        Job active = runningWithLease(Instant.now().plusSeconds(60), 1);

        assertThat(recoverer.recoverOnce()).isZero();

        assertThat(stored(active).status()).isEqualTo(JobStatus.RUNNING);
        assertThat(events(active)).isEmpty();
    }

    @Test
    void renewedLeaseBetweenReadAndRecoveryLeavesTheJobUntouched() {
        // El recuperador lee el trabajo vencido, pero su worker renueva justo antes de que se escriba.
        Job abandoned = runningWithLease(Instant.now().minusSeconds(5), 1);
        Job staleRead = jobs.findExpired(Instant.now(), 10).getFirst();
        assertThat(jobs.renewLease(abandoned.id(), 1, Instant.now(), Instant.now().plusSeconds(60))).isTrue();

        assertThat(processor.recover(staleRead)).isFalse();

        assertThat(stored(abandoned).status()).isEqualTo(JobStatus.RUNNING);
        assertThat(events(abandoned)).isEmpty();
    }

    @Test
    void recoveringTwiceDoesNotDuplicateTheRetry() {
        Job abandoned = runningWithLease(Instant.now().minusSeconds(5), 1);
        Job staleRead = jobs.findExpired(Instant.now(), 10).getFirst();

        assertThat(processor.recover(staleRead)).isTrue();
        assertThat(processor.recover(staleRead)).isFalse();

        assertThat(events(abandoned)).hasSize(1);
    }

    @Test
    void recoveredJobIsClaimableAgainAndAStragglerCannotOverwriteTheNewAttempt() {
        Job abandoned = runningWithLease(Instant.now().minusSeconds(5), 1);
        recoverer.recoverOnce();

        Job second = jobs.claim(abandoned.id(), Instant.now(), Instant.now().plusSeconds(60)).orElseThrow();
        assertThat(second.attempts()).isEqualTo(2);

        // El worker que se daba por muerto despierta y trata de cerrar su intento 1: no puede.
        assertThat(jobs.finishAttempt(abandoned.completed("{}", Instant.now()))).isFalse();
        assertThat(jobs.renewLease(abandoned.id(), 1, Instant.now(), Instant.now().plusSeconds(60))).isFalse();
        assertThat(stored(abandoned).status()).isEqualTo(JobStatus.RUNNING);
        assertThat(stored(abandoned).attempts()).isEqualTo(2);
    }

    @Test
    void heartbeatKeepsTheLeaseAliveDuringALongExecution() throws Exception {
        Job job = Job.queued(UUID.randomUUID(), "csv-import", Instant.now());
        jobs.insert(job);
        AtomicReference<Boolean> recoveredWhileRunning = new AtomicReference<>();
        AtomicReference<Instant> leaseAtStart = new AtomicReference<>();
        AtomicReference<Instant> leaseAtEnd = new AtomicReference<>();
        doAnswer(invocation -> {
            leaseAtStart.set(leaseOf(job));
            // Más del doble del lease: sin latido ya habría vencido y el recuperador lo tomaría.
            Thread.sleep(2_000);
            recoveredWhileRunning.set(recoverer.recoverOnce() > 0);
            leaseAtEnd.set(leaseOf(job));
            return "{}";
        }).when(executor).execute(any());

        processor.process(JobMessage.forJob(job.id()));

        assertThat(recoveredWhileRunning.get()).isFalse();
        assertThat(leaseAtEnd.get()).isAfter(leaseAtStart.get());
        assertThat(stored(job).status()).isEqualTo(JobStatus.COMPLETED);
        assertThat(leaseOf(job)).isNull();
    }

    @Test
    void finishingAnAttemptClearsTheLease() {
        Job job = Job.queued(UUID.randomUUID(), "csv-import", Instant.now());
        jobs.insert(job);

        processor.process(JobMessage.forJob(job.id()));

        assertThat(stored(job).status()).isEqualTo(JobStatus.COMPLETED);
        assertThat(leaseOf(job)).isNull();
    }
}
