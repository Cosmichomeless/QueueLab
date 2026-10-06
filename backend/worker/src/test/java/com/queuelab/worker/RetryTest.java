package com.queuelab.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

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
import com.queuelab.core.outbox.OutboxEvent;
import com.queuelab.core.outbox.OutboxRepository;
import com.queuelab.worker.job.JobExecutionException;
import com.queuelab.worker.job.JobExecutor;
import com.queuelab.worker.job.JobProcessor;
import com.queuelab.worker.job.TransientJobException;
import com.queuelab.worker.support.ContainersTestConfiguration;

/** Reintentos acotados con espera exponencial: 3 intentos en total, esperas de 2 s y 4 s. */
@SpringBootTest(properties = {
        "queuelab.worker.retry.max-attempts=3",
        "queuelab.worker.retry.initial-delay=2s",
        "queuelab.worker.retry.multiplier=2.0",
        "queuelab.worker.retry.max-delay=1m"})
@Import(ContainersTestConfiguration.class)
class RetryTest {

    @Autowired
    JobProcessor processor;

    @Autowired
    JobRepository jobs;

    @Autowired
    OutboxRepository outbox;

    @Autowired
    JdbcClient jdbc;

    @MockitoSpyBean
    JobExecutor executor;

    @BeforeEach
    void clean() {
        org.mockito.Mockito.reset(executor);
        jdbc.sql("DELETE FROM jobs").update();
    }

    private Job storedJob() {
        Job job = Job.queued(UUID.randomUUID(), "noop", Instant.now());
        jobs.insert(job);
        return job;
    }

    private Job stored(Job job) {
        return jobs.findById(job.id()).orElseThrow();
    }

    private Duration delayOfLastEvent(Job job) {
        return jdbc.sql("SELECT available_at - created_at FROM outbox_events WHERE job_id = :id "
                        + "ORDER BY created_at DESC LIMIT 1")
                .param("id", job.id()).query((rs, i) -> Duration.ofMillis(
                        (long) (rs.getObject(1, org.postgresql.util.PGInterval.class).getSeconds() * 1000)))
                .single();
    }

    @Test
    void transientFailureMovesToRetryingAndSchedulesTheNextAttemptWithBackoff() {
        Job job = storedJob();
        doThrow(new TransientJobException("El servicio externo no responde")).when(executor).execute(any());

        processor.process(JobMessage.forJob(job.id()));

        Job retrying = stored(job);
        assertThat(retrying.status()).isEqualTo(JobStatus.RETRYING);
        assertThat(retrying.attempts()).isEqualTo(1);
        assertThat(retrying.error()).isEqualTo("El servicio externo no responde");
        assertThat(retrying.finishedAt()).isNull();
        assertThat(retrying.startedAt()).isNotNull();
        // Un evento nuevo, no publicado, con la espera inicial de 2 s.
        var events = outbox.findByJobId(job.id());
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().isPublished()).isFalse();
        assertThat(delayOfLastEvent(job)).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void waitGrowsWithEachAttemptAndTheJobEndsFailedWhenAttemptsRunOut() {
        Job job = storedJob();
        doThrow(new TransientJobException("sigue fallando")).when(executor).execute(any());

        processor.process(JobMessage.forJob(job.id())); // intento 1 → espera 2 s
        assertThat(delayOfLastEvent(job)).isEqualTo(Duration.ofSeconds(2));
        processor.process(JobMessage.forJob(job.id())); // intento 2 → espera 4 s
        assertThat(stored(job).status()).isEqualTo(JobStatus.RETRYING);
        assertThat(stored(job).attempts()).isEqualTo(2);
        assertThat(delayOfLastEvent(job)).isEqualTo(Duration.ofSeconds(4));
        processor.process(JobMessage.forJob(job.id())); // intento 3 → se acabaron

        Job failed = stored(job);
        assertThat(failed.status()).isEqualTo(JobStatus.FAILED);
        assertThat(failed.attempts()).isEqualTo(3);
        assertThat(failed.error()).isEqualTo("sigue fallando");
        assertThat(failed.finishedAt()).isNotNull();
        // Dos reintentos programados + el aviso a la dead-letter, y ningún reintento más.
        assertThat(outbox.findByJobId(job.id())).extracting(OutboxEvent::eventType)
                .containsExactly("JOB_QUEUED", "JOB_QUEUED", "JOB_DEAD_LETTERED");

        // Una entrega más no revive un trabajo FAILED: no hay bucle.
        processor.process(JobMessage.forJob(job.id()));
        verify(executor, times(3)).execute(any());
        assertThat(stored(job)).isEqualTo(failed);
    }

    @Test
    void permanentFailureIsNotRetried() {
        Job job = storedJob();
        doThrow(new JobExecutionException("El CSV está vacío")).when(executor).execute(any());

        processor.process(JobMessage.forJob(job.id()));

        Job failed = stored(job);
        assertThat(failed.status()).isEqualTo(JobStatus.FAILED);
        assertThat(failed.attempts()).isEqualTo(1);
        assertThat(outbox.findByJobId(job.id())).isEmpty();
    }

    @Test
    void unexpectedFailureIsNotRetriedEither() {
        Job job = storedJob();
        doThrow(new IllegalStateException("bug")).when(executor).execute(any());

        processor.process(JobMessage.forJob(job.id()));

        assertThat(stored(job).status()).isEqualTo(JobStatus.FAILED);
        assertThat(outbox.findByJobId(job.id())).isEmpty();
    }

    @Test
    void retrySucceedingClearsTheLastError() {
        Job job = storedJob();
        doThrow(new TransientJobException("fallo puntual")).doReturn("hecho").when(executor).execute(any());

        processor.process(JobMessage.forJob(job.id()));
        assertThat(stored(job).status()).isEqualTo(JobStatus.RETRYING);
        processor.process(JobMessage.forJob(job.id()));

        Job done = stored(job);
        assertThat(done.status()).isEqualTo(JobStatus.COMPLETED);
        assertThat(done.attempts()).isEqualTo(2);
        assertThat(done.result()).isEqualTo("hecho");
        assertThat(done.error()).isNull();
        assertThat(done.finishedAt()).isNotNull();
    }

    @Test
    void jobRetryingRetainsItsOriginalStartTime() {
        Job job = storedJob();
        doThrow(new TransientJobException("x")).doReturn("ok").when(executor).execute(any());

        processor.process(JobMessage.forJob(job.id()));
        Instant firstStart = stored(job).startedAt();
        processor.process(JobMessage.forJob(job.id()));

        assertThat(stored(job).startedAt()).isEqualTo(firstStart);
    }

    @Test
    void exhaustedJobRecordsADeadLetterEventWithCauseAndAttemptsAndNothingElse() {
        Job job = storedJob();
        doThrow(new TransientJobException("sigue fallando")).when(executor).execute(any());

        processor.process(JobMessage.forJob(job.id()));
        processor.process(JobMessage.forJob(job.id()));
        processor.process(JobMessage.forJob(job.id()));

        OutboxEvent dead = outbox.findByJobId(job.id()).getLast();
        assertThat(dead.eventType()).isEqualTo(OutboxEvent.JOB_DEAD_LETTERED);
        assertThat(dead.isPublished()).isFalse();
        // Disponible de inmediato (sin espera) y con solo id, intentos y causa.
        assertThat(jdbc.sql("SELECT available_at IS NULL FROM outbox_events WHERE id = :id")
                .param("id", dead.id()).query(Boolean.class).single()).isTrue();
        // jsonb normaliza el orden y los espacios: se compara el contenido, no el texto.
        var body = tools.jackson.databind.json.JsonMapper.builder().build().readTree(dead.payload());
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("version", "jobId", "attempts", "cause");
        assertThat(body.get("jobId").asString()).isEqualTo(job.id().toString());
        assertThat(body.get("attempts").asInt()).isEqualTo(3);
        assertThat(body.get("cause").asString()).isEqualTo("sigue fallando");
    }

    @Test
    void permanentFailureDoesNotGoToTheDeadLetterQueue() {
        Job job = storedJob();
        doThrow(new JobExecutionException("El CSV está vacío")).when(executor).execute(any());

        processor.process(JobMessage.forJob(job.id()));

        assertThat(outbox.findByJobId(job.id())).extracting(OutboxEvent::eventType)
                .doesNotContain(OutboxEvent.JOB_DEAD_LETTERED);
    }
}
