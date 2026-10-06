package com.queuelab.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.queuelab.core.job.Job;
import com.queuelab.core.job.JobRepository;
import com.queuelab.core.job.JobStatus;
import com.queuelab.core.messaging.JobMessage;
import com.queuelab.core.messaging.JobMessageCodec;
import com.queuelab.core.messaging.JobMessagingTopology;
import com.queuelab.worker.job.JobExecutionException;
import com.queuelab.worker.job.JobExecutor;
import com.queuelab.worker.support.ContainersTestConfiguration;

/** El worker consume de un RabbitMQ real y carga el trabajo de un PostgreSQL real. */
@SpringBootTest(properties = "spring.rabbitmq.listener.simple.auto-startup=true")
@Import(ContainersTestConfiguration.class)
class JobConsumerTest {

    @Autowired
    RabbitTemplate rabbit;

    @Autowired
    RabbitAdmin admin;

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
        admin.purgeQueue(JobMessagingTopology.DEAD_LETTER_QUEUE);
    }

    private Job storedJob(String type) {
        Job job = Job.queued(UUID.randomUUID(), type, Instant.now());
        jobs.insert(job);
        return job;
    }

    private void publish(Message message) {
        rabbit.send(JobMessagingTopology.EXCHANGE, JobMessagingTopology.ROUTING_KEY, message);
    }

    private Message deadLetter() {
        return rabbit.receive(JobMessagingTopology.DEAD_LETTER_QUEUE, 5_000);
    }

    private void assertMainQueueIsEmpty() {
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var info = admin.getQueueInfo(JobMessagingTopology.QUEUE);
            assertThat(info.getMessageCount()).isZero();
        });
    }

    @Test
    void validMessageLoadsTheJobFromTheDatabaseAndExecutesIt() {
        Job job = storedJob("noop");

        publish(JobMessageCodec.encode(JobMessage.forJob(job.id())));

        verify(executor, timeout(10_000)).execute(argThat(
                loaded -> loaded.id().equals(job.id()) && loaded.type().equals("noop")));
        assertMainQueueIsEmpty();
        assertThat(rabbit.receive(JobMessagingTopology.DEAD_LETTER_QUEUE, 300)).isNull();
    }

    @Test
    void malformedMessagesGoToTheDeadLetterQueueAndDoNotBlockTheQueue() {
        Job job = storedJob("noop");

        publish(new Message("basura".getBytes()));
        publish(new Message("{\"version\":99,\"jobId\":\"x\"}".getBytes()));
        publish(new Message("{\"version\":1,\"jobId\":\"no-es-uuid\"}".getBytes()));
        publish(JobMessageCodec.encode(JobMessage.forJob(job.id())));

        // El mensaje válido que viene detrás de los malformados se procesa igualmente.
        verify(executor, timeout(10_000)).execute(argThat(loaded -> loaded.id().equals(job.id())));
        assertThat(deadLetter()).isNotNull();
        assertThat(deadLetter()).isNotNull();
        assertThat(deadLetter()).isNotNull();
        assertMainQueueIsEmpty();
    }

    @Test
    void messageForAnUnknownJobGoesToTheDeadLetterQueue() {
        UUID missing = UUID.randomUUID();

        publish(JobMessageCodec.encode(JobMessage.forJob(missing)));

        Message dead = deadLetter();
        assertThat(dead).isNotNull();
        assertThat(JobMessageCodec.decode(dead).jobId()).isEqualTo(missing);
        verify(executor, never()).execute(any());
        assertMainQueueIsEmpty();
    }

    @Test
    void validMessageEndsCompletedWithItsResult() {
        Job job = storedJob("noop");

        publish(JobMessageCodec.encode(JobMessage.forJob(job.id())));

        Job done = awaitStatus(job.id(), JobStatus.COMPLETED);
        assertThat(done.result()).isEqualTo("Trabajo de tipo 'noop' completado");
        assertThat(done.error()).isNull();
        assertThat(done.startedAt()).isNotNull();
        assertThat(done.finishedAt()).isNotNull().isAfterOrEqualTo(done.startedAt());
        assertThat(done.updatedAt()).isAfter(job.updatedAt());
    }

    @Test
    void jobIsRunningInTheDatabaseWhileItExecutes() {
        Job job = storedJob("noop");
        var seenWhileRunning = new java.util.concurrent.atomic.AtomicReference<JobStatus>();
        doAnswer(invocation -> {
            seenWhileRunning.set(jobs.findById(job.id()).orElseThrow().status());
            return "ok";
        }).when(executor).execute(any());

        publish(JobMessageCodec.encode(JobMessage.forJob(job.id())));

        awaitStatus(job.id(), JobStatus.COMPLETED);
        assertThat(seenWhileRunning.get()).isEqualTo(JobStatus.RUNNING);
    }

    @Test
    void expectedFailureEndsFailedWithItsSummary() {
        Job job = storedJob("noop");
        doThrow(new JobExecutionException("El fichero CSV está vacío")).when(executor).execute(any());

        publish(JobMessageCodec.encode(JobMessage.forJob(job.id())));

        Job failed = awaitStatus(job.id(), JobStatus.FAILED);
        assertThat(failed.error()).isEqualTo("El fichero CSV está vacío");
        assertThat(failed.result()).isNull();
        assertThat(failed.finishedAt()).isNotNull();
        // El resultado persistido ya es definitivo: el mensaje se confirma, no va a la DLQ ni se reentrega.
        assertMainQueueIsEmpty();
        assertThat(rabbit.receive(JobMessagingTopology.DEAD_LETTER_QUEUE, 300)).isNull();
    }

    @Test
    void unexpectedFailureStoresAGenericErrorWithoutLeakingDetails() {
        Job job = storedJob("noop");
        doThrow(new IllegalStateException("jdbc:postgresql://interno:5432/db password=s3cr3t"))
                .when(executor).execute(any());

        publish(JobMessageCodec.encode(JobMessage.forJob(job.id())));

        Job failed = awaitStatus(job.id(), JobStatus.FAILED);
        assertThat(failed.error()).isEqualTo("Error inesperado durante la ejecución");
        assertThat(failed.error()).doesNotContain("s3cr3t").doesNotContain("jdbc");
        verify(executor, timeout(2_000).times(1)).execute(any());
    }

    @Test
    void longErrorSummaryIsTruncatedToTheColumnSize() {
        Job job = storedJob("noop");
        doThrow(new JobExecutionException("x".repeat(2_000))).when(executor).execute(any());

        publish(JobMessageCodec.encode(JobMessage.forJob(job.id())));

        assertThat(awaitStatus(job.id(), JobStatus.FAILED).error()).hasSize(Job.ERROR_MAX_LENGTH);
    }

    @Test
    void duplicateMessageForAnAlreadyFinishedJobIsIgnored() {
        Job job = storedJob("noop");
        publish(JobMessageCodec.encode(JobMessage.forJob(job.id())));
        Job done = awaitStatus(job.id(), JobStatus.COMPLETED);

        publish(JobMessageCodec.encode(JobMessage.forJob(job.id())));

        assertMainQueueIsEmpty();
        verify(executor, timeout(1_000).times(1)).execute(any());
        assertThat(jobs.findById(job.id()).orElseThrow()).isEqualTo(done);
    }

    private Job awaitStatus(UUID id, JobStatus status) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(
                () -> assertThat(jobs.findById(id).orElseThrow().status()).isEqualTo(status));
        return jobs.findById(id).orElseThrow();
    }
}
