package com.queuelab.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
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

/** El límite de consumidores simultáneos se respeta con la cola llena, contra RabbitMQ y PostgreSQL reales. */
@SpringBootTest(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=true",
        "queuelab.worker.concurrency=3",
        "queuelab.worker.prefetch=1" })
@Import(ContainersTestConfiguration.class)
class ConcurrencyTest {

    private static final int JOBS = 12;
    private static final long EXECUTION_MILLIS = 300;

    @Autowired
    RabbitTemplate rabbit;

    @Autowired
    JobRepository jobs;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    RabbitListenerEndpointRegistry registry;

    @MockitoSpyBean
    JobExecutor executor;

    final AtomicInteger running = new AtomicInteger();
    final AtomicInteger peak = new AtomicInteger();
    final AtomicInteger finished = new AtomicInteger();

    @BeforeEach
    void clean() {
        org.mockito.Mockito.reset(executor);
        jdbc.sql("DELETE FROM jobs").update();
    }

    @Test
    void neverRunsMoreJobsAtOnceThanConfiguredAndReachesTheLimitUnderLoad() {
        doAnswer(invocation -> {
            peak.accumulateAndGet(running.incrementAndGet(), Math::max);
            try {
                Thread.sleep(EXECUTION_MILLIS);
                return "ok";
            } finally {
                running.decrementAndGet();
                finished.incrementAndGet();
            }
        }).when(executor).execute(any());

        // Todos los mensajes a la vez: la cola está saturada desde el primer instante.
        IntStream.range(0, JOBS).forEach(i -> {
            Job job = Job.queued(UUID.randomUUID(), "noop", Instant.now());
            jobs.insert(job);
            rabbit.send(JobMessagingTopology.EXCHANGE, JobMessagingTopology.ROUTING_KEY,
                    JobMessageCodec.encode(JobMessage.forJob(job.id())));
        });

        await().atMost(Duration.ofSeconds(30)).untilAtomic(finished, org.hamcrest.Matchers.equalTo(JOBS));
        assertThat(peak.get()).as("máximo de trabajos simultáneos").isEqualTo(3);
        assertThat(running.get()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM jobs WHERE status = 'COMPLETED'").query(Long.class).single())
                .isEqualTo((long) JOBS);
    }

    @Test
    void containerUsesAFixedNumberOfConsumersAndPrefetchOfOne() {
        var container = (SimpleMessageListenerContainer) registry.getListenerContainers().iterator().next();
        assertThat(container.getActiveConsumerCount()).isEqualTo(3);
        assertThat(container).extracting("concurrentConsumers", "maxConcurrentConsumers", "prefetchCount")
                .containsExactly(3, 3, 1);
    }
}
