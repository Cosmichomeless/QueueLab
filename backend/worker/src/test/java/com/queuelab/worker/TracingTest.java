package com.queuelab.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.queuelab.core.job.Job;
import com.queuelab.core.job.JobRepository;
import com.queuelab.core.job.JobStatus;
import com.queuelab.core.messaging.JobMessage;
import com.queuelab.core.messaging.JobMessageCodec;
import com.queuelab.core.messaging.JobMessagingTopology;
import com.queuelab.core.tracing.JobTracing;
import com.queuelab.worker.job.AbandonedJobRecoverer;
import com.queuelab.worker.job.JobExecutionException;
import com.queuelab.worker.job.JobExecutor;
import com.queuelab.worker.job.TransientJobException;
import com.queuelab.worker.support.ContainersTestConfiguration;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

/**
 * La traza de un trabajo en el worker: continúa la del despachador (cabecera {@code traceparent}), muestra la
 * espera en la cola y la ejecución, y los reintentos y avisos a la DLQ que guarda heredan la misma traza. Spans
 * reales recogidos en memoria, con RabbitMQ y PostgreSQL reales.
 */
@SpringBootTest(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=true",
        "queuelab.worker.retry.max-attempts=2",
        "queuelab.worker.retry.initial-delay=2s",
        "queuelab.worker.retry.max-delay=1m"})
@Import({ContainersTestConfiguration.class, TracingTest.Spans.class})
class TracingTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class Spans {
        @Bean
        InMemorySpanExporter spanExporter() {
            return InMemorySpanExporter.create();
        }

        @Bean
        SpanProcessor inMemorySpanProcessor(InMemorySpanExporter exporter) {
            return SimpleSpanProcessor.create(exporter);
        }
    }

    private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";
    private static final String PARENT_SPAN_ID = "b7ad6b7169203331";
    private static final String TRACEPARENT = "00-" + TRACE_ID + "-" + PARENT_SPAN_ID + "-01";

    @Autowired
    RabbitTemplate rabbit;

    @Autowired
    JobRepository jobs;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    InMemorySpanExporter spans;

    @Autowired
    AbandonedJobRecoverer recoverer;

    @MockitoSpyBean
    JobExecutor executor;

    @BeforeEach
    void clean() {
        org.mockito.Mockito.reset(executor);
        jdbc.sql("DELETE FROM jobs").update();
        spans.reset();
    }

    private Job storedJob() {
        Job job = Job.queued(UUID.randomUUID(), "noop", Instant.now());
        jobs.insert(job);
        return job;
    }

    private void publish(Job job, String traceparent, Instant enqueuedAt) {
        Message message = JobMessageCodec.encodeJson(JobMessageCodec.toJson(JobMessage.forJob(job.id())),
                UUID.randomUUID().toString(), traceparent, enqueuedAt);
        rabbit.send(JobMessagingTopology.EXCHANGE, JobMessagingTopology.ROUTING_KEY, message);
    }

    private void awaitStatus(Job job, JobStatus status) {
        await().atMost(Duration.ofSeconds(15)).untilAsserted(
                () -> assertThat(jobs.findById(job.id()).orElseThrow().status()).isEqualTo(status));
    }

    private SpanData span(String name) {
        // El span se cierra después de guardar el estado final: se espera a verlo en el exportador.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(spans.getFinishedSpanItems().stream()
                .map(SpanData::getName)).contains(name));
        List<SpanData> found = spans.getFinishedSpanItems().stream().filter(s -> s.getName().equals(name)).toList();
        assertThat(found).as("spans «%s»", name).hasSize(1);
        return found.get(0);
    }

    private String storedTrace(Job job, String eventType) {
        return jdbc.sql("SELECT trace_context FROM outbox_events WHERE job_id = :id AND event_type = :type")
                .param("id", job.id()).param("type", eventType).query(String.class).single();
    }

    @Test
    void theWorkerContinuesTheTraceOfThePublisherAndShowsTheWaitAndTheExecution() {
        Job job = storedJob();
        Instant enqueuedAt = Instant.now().minusMillis(400);

        publish(job, TRACEPARENT, enqueuedAt);
        awaitStatus(job, JobStatus.COMPLETED);

        SpanData wait = span("queue.wait");
        SpanData process = span("job.process");
        SpanData execute = span("job.execute");
        assertThat(wait.getTraceId()).isEqualTo(TRACE_ID);
        assertThat(process.getTraceId()).isEqualTo(TRACE_ID);
        assertThat(execute.getTraceId()).isEqualTo(TRACE_ID);
        assertThat(wait.getParentSpanId()).isEqualTo(PARENT_SPAN_ID);
        assertThat(process.getParentSpanId()).isEqualTo(PARENT_SPAN_ID);
        assertThat(execute.getParentSpanId()).isEqualTo(process.getSpanId());
        assertThat(process.getKind()).isEqualTo(SpanKind.CONSUMER);

        // La espera va de la entrega al broker a la recepción, y el procesamiento empieza donde acaba.
        assertThat(wait.getStartEpochNanos() / 1_000_000).isEqualTo(enqueuedAt.toEpochMilli());
        assertThat(wait.getEndEpochNanos()).isEqualTo(process.getStartEpochNanos());
        assertThat(process.getStartEpochNanos()).isLessThanOrEqualTo(execute.getStartEpochNanos());
        assertThat(execute.getEndEpochNanos()).isLessThanOrEqualTo(process.getEndEpochNanos());
        assertThat(process.getAttributes().asMap().values()).contains(job.id().toString());
        assertThat(execute.getAttributes().asMap().values()).contains(job.id().toString(), "noop");
        assertThat(process.getStatus().getStatusCode()).isNotEqualTo(StatusCode.ERROR);
    }

    @Test
    void aMessageWithoutTraceHeadersStartsItsOwnTraceAndHasNoQueueWait() {
        Job job = storedJob();

        publish(job, null, null);
        awaitStatus(job, JobStatus.COMPLETED);

        SpanData process = span("job.process");
        assertThat(process.getParentSpanId()).isEqualTo("0000000000000000");
        assertThat(span("job.execute").getTraceId()).isEqualTo(process.getTraceId());
        assertThat(spans.getFinishedSpanItems().stream().map(SpanData::getName)).doesNotContain("queue.wait");
    }

    @Test
    void aCorruptTraceparentNeverBlocksTheJob() {
        Job job = storedJob();

        publish(job, "esto-no-es-un-traceparent", Instant.now());
        awaitStatus(job, JobStatus.COMPLETED);

        assertThat(span("job.process").getParentSpanId()).isEqualTo("0000000000000000");
    }

    @Test
    void aClockAheadOfTheWorkerNeverProducesANegativeWait() {
        Job job = storedJob();

        publish(job, TRACEPARENT, Instant.now().plusSeconds(30));
        awaitStatus(job, JobStatus.COMPLETED);

        SpanData wait = span("queue.wait");
        assertThat(wait.getEndEpochNanos()).isGreaterThanOrEqualTo(wait.getStartEpochNanos());
    }

    @Test
    void aFailedExecutionMarksTheExecuteSpanAsAnErrorAndTheRetryKeepsTheTrace() {
        Job job = storedJob();
        doThrow(new TransientJobException("el servicio externo no responde")).when(executor).execute(any());

        publish(job, TRACEPARENT, Instant.now());
        awaitStatus(job, JobStatus.RETRYING);

        SpanData execute = span("job.execute");
        assertThat(execute.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        // El reintento se guarda dentro del procesamiento: el evento lleva el contexto de job.process, y con él
        // el siguiente intento cuelga de la misma traza.
        var retry = JobTracing.parse(storedTrace(job, "JOB_QUEUED"));
        assertThat(retry).isNotNull();
        assertThat(retry.getTraceId()).isEqualTo(TRACE_ID);
        assertThat(retry.getSpanId()).isEqualTo(span("job.process").getSpanId());
    }

    @Test
    void anExpectedFailureLeavesTheProcessSpanClean() {
        Job job = storedJob();
        doThrow(new JobExecutionException("El fichero CSV está vacío")).when(executor).execute(any());

        publish(job, TRACEPARENT, Instant.now());
        awaitStatus(job, JobStatus.FAILED);

        // Un fallo esperado es un resultado del trabajo, no un error del procesamiento del mensaje.
        assertThat(span("job.process").getStatus().getStatusCode()).isNotEqualTo(StatusCode.ERROR);
        assertThat(span("job.execute").getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
    }

    @Test
    void theDeadLetterNoticeKeepsTheTraceOfTheLastAttempt() {
        Job job = storedJob();
        doThrow(new TransientJobException("sigue fallando")).when(executor).execute(any());

        // Con max-attempts=2 el segundo intento agota los reintentos: se procesa directamente, sin esperar el backoff.
        publish(job, TRACEPARENT, Instant.now());
        awaitStatus(job, JobStatus.RETRYING);
        jdbc.sql("DELETE FROM outbox_events WHERE job_id = :id").param("id", job.id()).update();
        spans.reset();
        publish(job, TRACEPARENT, Instant.now());
        awaitStatus(job, JobStatus.FAILED);

        var dead = JobTracing.parse(storedTrace(job, "JOB_DEAD_LETTERED"));
        assertThat(dead).isNotNull();
        assertThat(dead.getTraceId()).isEqualTo(TRACE_ID);
        assertThat(dead.getSpanId()).isEqualTo(span("job.process").getSpanId());
    }

    @Test
    void recoveringAnAbandonedJobOpensItsOwnTraceAndTheEventInheritsIt() {
        Job job = storedJob();
        jobs.claim(job.id(), Instant.now().minusSeconds(60), Instant.now().minusSeconds(5)).orElseThrow();

        assertThat(recoverer.recoverOnce()).isEqualTo(1);

        SpanData recover = span("job.recover");
        assertThat(recover.getParentSpanId()).isEqualTo("0000000000000000");
        assertThat(recover.getAttributes().asMap().values()).contains(job.id().toString());
        var retry = JobTracing.parse(storedTrace(job, "JOB_QUEUED"));
        assertThat(retry).isNotNull();
        assertThat(retry.getTraceId()).isEqualTo(recover.getTraceId());
        assertThat(retry.getSpanId()).isEqualTo(recover.getSpanId());
    }

    @Test
    void aMalformedMessageMarksTheProcessSpanAsAnError() {
        rabbit.send(JobMessagingTopology.EXCHANGE, JobMessagingTopology.ROUTING_KEY,
                new Message("esto no es json".getBytes()));

        SpanData process = span("job.process");

        assertThat(process.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(spans.getFinishedSpanItems().stream().map(SpanData::getName)).doesNotContain("job.execute");
    }
}
