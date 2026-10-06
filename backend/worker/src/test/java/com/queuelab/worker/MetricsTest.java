package com.queuelab.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
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
import com.queuelab.worker.job.JobExecutionException;
import com.queuelab.worker.job.JobExecutor;
import com.queuelab.worker.job.JobProcessor;
import com.queuelab.worker.job.TransientJobException;
import com.queuelab.worker.metrics.MetricsHttpServer;
import com.queuelab.worker.support.ContainersTestConfiguration;

import io.micrometer.core.instrument.MeterRegistry;

/** Métricas del worker: lo que se mide en cada resultado y que se puede consultar por HTTP en formato Prometheus. */
@SpringBootTest(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=true",
        "queuelab.worker.retry.max-attempts=3",
        "queuelab.worker.retry.initial-delay=2s",
        "queuelab.worker.retry.max-delay=1m"})
@Import(ContainersTestConfiguration.class)
class MetricsTest {

    @Autowired
    JobProcessor processor;

    @Autowired
    JobRepository jobs;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MeterRegistry registry;

    @Autowired
    MetricsHttpServer metricsServer;

    @Autowired
    RabbitTemplate rabbit;

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

    private double executions(String outcome) {
        var timer = registry.find("queuelab.job.execution").tag("type", "noop").tag("outcome", outcome).timer();
        return timer == null ? 0 : timer.count();
    }

    private double waits() {
        var timer = registry.find("queuelab.job.wait").tag("type", "noop").timer();
        return timer == null ? 0 : timer.count();
    }

    private double counter(String name, String tag, String value) {
        var counter = registry.find(name).tag("type", "noop").tag(tag, value).counter();
        return counter == null ? 0 : counter.count();
    }

    private double rejected(String reason) {
        var counter = registry.find("queuelab.messages.rejected").tag("reason", reason).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void completedJobRecordsItsExecutionAndItsWait() {
        double completed = executions("completed");
        double waited = waits();
        Job job = storedJob();

        processor.process(JobMessage.forJob(job.id()));

        assertThat(executions("completed")).isEqualTo(completed + 1);
        assertThat(waits()).isEqualTo(waited + 1);
    }

    @Test
    void retriesAndDeadLettersAreCountedByCauseAndTheWaitOnlyOnTheFirstAttempt() {
        double retried = counter("queuelab.job.retries", "cause", "transient");
        double dead = counter("queuelab.job.dead_letters", "cause", "transient");
        double retryRuns = executions("retry");
        double deadRuns = executions("dead_letter");
        double waited = waits();
        Job job = storedJob();
        doThrow(new TransientJobException("sigue fallando")).when(executor).execute(any());

        processor.process(JobMessage.forJob(job.id()));
        processor.process(JobMessage.forJob(job.id()));
        processor.process(JobMessage.forJob(job.id()));

        assertThat(counter("queuelab.job.retries", "cause", "transient")).isEqualTo(retried + 2);
        assertThat(counter("queuelab.job.dead_letters", "cause", "transient")).isEqualTo(dead + 1);
        assertThat(executions("retry")).isEqualTo(retryRuns + 2);
        assertThat(executions("dead_letter")).isEqualTo(deadRuns + 1);
        assertThat(waits()).isEqualTo(waited + 1);
    }

    @Test
    void permanentFailureCountsAsFailedAndNotAsARetry() {
        double failed = executions("failed");
        double retried = counter("queuelab.job.retries", "cause", "transient");
        Job job = storedJob();
        doThrow(new JobExecutionException("El CSV está vacío")).when(executor).execute(any());

        processor.process(JobMessage.forJob(job.id()));

        assertThat(executions("failed")).isEqualTo(failed + 1);
        assertThat(counter("queuelab.job.retries", "cause", "transient")).isEqualTo(retried);
    }

    @Test
    void rejectedMessagesAreCountedByReason() {
        double malformed = rejected("malformed");
        double unknown = rejected("unknown_job");

        rabbit.send(JobMessagingTopology.EXCHANGE, JobMessagingTopology.ROUTING_KEY,
                new Message("esto no es json".getBytes()));
        rabbit.send(JobMessagingTopology.EXCHANGE, JobMessagingTopology.ROUTING_KEY,
                JobMessageCodec.encode(JobMessage.forJob(UUID.randomUUID())));

        await().atMost(java.time.Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(rejected("malformed")).isEqualTo(malformed + 1);
            assertThat(rejected("unknown_job")).isEqualTo(unknown + 1);
        });
    }

    @Test
    void exposesPrometheusTextOverHttpWithBoundedLabelsAndNoJobIds() throws Exception {
        Job job = storedJob();
        processor.process(JobMessage.forJob(job.id()));

        HttpClient http = HttpClient.newHttpClient();
        URI uri = URI.create("http://127.0.0.1:" + metricsServer.port() + "/metrics");
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("text/plain"));
        String body = response.body();
        assertThat(body.lines().filter(line -> line.startsWith("queuelab_job_execution_seconds_count")))
                .anySatisfy(line -> assertThat(line)
                        .contains("outcome=\"completed\"", "type=\"noop\"", "application=\"queuelab-worker\""));
        assertThat(body).contains("queuelab_job_wait_seconds_bucket", "jvm_memory_used_bytes");
        // Ni el id del trabajo ni el de correlación son nunca una etiqueta.
        assertThat(body).doesNotContain(job.id().toString());
        assertThat(body).doesNotContain("jobId").doesNotContain("correlationId");

        HttpResponse<String> post = http.send(HttpRequest.newBuilder(uri)
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(post.statusCode()).isEqualTo(405);
    }

    @Test
    void unexpectedJobTypesAreGroupedSoLabelsStayBounded() {
        Job job = Job.queued(UUID.randomUUID(), "Tipo con Espacios Y MAYÚSCULAS " + UUID.randomUUID(), Instant.now());
        jobs.insert(job);

        processor.process(JobMessage.forJob(job.id()));

        assertThat(registry.find("queuelab.job.execution").tag("type", "other").tag("outcome", "failed")
                .timer()).isNotNull();
    }
}
