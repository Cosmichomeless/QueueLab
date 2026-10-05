package com.queuelab.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
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
import com.queuelab.core.messaging.JobMessage;
import com.queuelab.core.messaging.JobMessageCodec;
import com.queuelab.core.messaging.JobMessagingTopology;
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
        Job job = storedJob("csv-import");

        publish(JobMessageCodec.encode(JobMessage.forJob(job.id())));

        verify(executor, timeout(10_000)).execute(argThat(
                loaded -> loaded.id().equals(job.id()) && loaded.type().equals("csv-import")));
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
    void failingExecutionDoesNotRequeueInALoop() {
        Job job = storedJob("noop");
        doThrow(new IllegalStateException("fallo del trabajo")).when(executor).execute(any());

        publish(JobMessageCodec.encode(JobMessage.forJob(job.id())));

        assertThat(deadLetter()).isNotNull();
        assertMainQueueIsEmpty();
        verify(executor, timeout(2_000).times(1)).execute(any());
    }
}
