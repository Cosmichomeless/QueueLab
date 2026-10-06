package com.queuelab.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import com.queuelab.api.outbox.OutboxDispatcher;
import com.queuelab.api.support.PostgresTestConfiguration;
import com.queuelab.api.support.RabbitTestConfiguration;
import com.queuelab.core.messaging.JobMessageCodec;
import com.queuelab.core.messaging.JobMessagingTopology;
import com.queuelab.core.tracing.JobTracing;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

/**
 * La traza de un trabajo en la API: la petición HTTP, la espera en el outbox y la publicación cuelgan de una sola
 * traza, y el mensaje lleva su contexto al worker. Spans reales, recogidos en memoria, con PostgreSQL y RabbitMQ
 * reales.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestConfiguration.class, RabbitTestConfiguration.class, TracingTest.Spans.class})
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

    @Autowired
    MockMvc mvc;

    @Autowired
    OutboxDispatcher dispatcher;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    RabbitAdmin admin;

    @Autowired
    RabbitTemplate rabbit;

    @Autowired
    InMemorySpanExporter spans;

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM jobs").update();
        admin.initialize();
        admin.purgeQueue(JobMessagingTopology.QUEUE);
        admin.purgeQueue(JobMessagingTopology.DEAD_LETTER_QUEUE);
        spans.reset();
    }

    private UUID submit() throws Exception {
        mvc.perform(post("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"noop\"}"))
                .andExpect(status().isCreated());
        return jdbc.sql("SELECT id FROM jobs ORDER BY created_at DESC LIMIT 1").query(UUID.class).single();
    }

    private SpanData span(String name) {
        List<SpanData> found = spans.getFinishedSpanItems().stream().filter(s -> s.getName().equals(name)).toList();
        assertThat(found).as("spans «%s» entre %s", name,
                spans.getFinishedSpanItems().stream().map(SpanData::getName).toList()).hasSize(1);
        return found.get(0);
    }

    private SpanData httpServerSpan() {
        List<SpanData> found = spans.getFinishedSpanItems().stream()
                .filter(s -> s.getKind() == SpanKind.SERVER).toList();
        assertThat(found).as("span HTTP de la petición").hasSize(1);
        return found.get(0);
    }

    @Test
    void oneTraceLinksTheRequestTheOutboxWaitAndThePublish() throws Exception {
        UUID jobId = submit();
        SpanData http = httpServerSpan();

        // El evento guarda el contexto del span de la petición para que el despachador siga la misma traza.
        String stored = jdbc.sql("SELECT trace_context FROM outbox_events WHERE job_id = :id")
                .param("id", jobId).query(String.class).single();
        assertThat(stored).startsWith("00-" + http.getTraceId() + "-");
        assertThat(JobTracing.parse(stored).getSpanId()).isEqualTo(http.getSpanId());
        Instant createdAt = jdbc.sql("SELECT created_at FROM outbox_events WHERE job_id = :id")
                .param("id", jobId).query(java.time.OffsetDateTime.class).single().toInstant();

        assertThat(dispatcher.dispatchPending()).isEqualTo(1);

        SpanData wait = span("outbox.wait");
        SpanData publish = span("outbox.publish");
        assertThat(wait.getTraceId()).isEqualTo(http.getTraceId());
        assertThat(publish.getTraceId()).isEqualTo(http.getTraceId());
        assertThat(wait.getParentSpanId()).isEqualTo(http.getSpanId());
        assertThat(publish.getParentSpanId()).isEqualTo(http.getSpanId());
        assertThat(publish.getKind()).isEqualTo(SpanKind.PRODUCER);

        // La espera cubre desde que se guardó el evento hasta que el despachador lo recoge; la publicación empieza ahí.
        assertThat(Instant.ofEpochSecond(0, wait.getStartEpochNanos())).isEqualTo(createdAt);
        assertThat(wait.getEndEpochNanos()).isEqualTo(publish.getStartEpochNanos());
        assertThat(publish.getEndEpochNanos()).isGreaterThanOrEqualTo(publish.getStartEpochNanos());
        assertThat(publish.getStatus().getStatusCode()).isNotEqualTo(StatusCode.ERROR);
        assertThat(publish.getAttributes().asMap().values()).contains(jobId.toString());
    }

    @Test
    void theMessageCarriesThePublishSpanAndTheDeliveryTimeToTheWorker() throws Exception {
        submit();
        dispatcher.dispatchPending();
        SpanData publish = span("outbox.publish");

        Message message = rabbit.receive(JobMessagingTopology.QUEUE, 5000);

        assertThat(message).isNotNull();
        var parent = JobMessageCodec.traceContextOf(message);
        assertThat(parent).isNotNull();
        assertThat(parent.getTraceId()).isEqualTo(publish.getTraceId());
        assertThat(parent.getSpanId()).isEqualTo(publish.getSpanId());
        Instant enqueuedAt = JobMessageCodec.enqueuedAtOf(message);
        assertThat(enqueuedAt).isBetween(Instant.ofEpochSecond(0, publish.getStartEpochNanos()).minusMillis(1),
                Instant.ofEpochSecond(0, publish.getEndEpochNanos()).plusMillis(1));
    }

    @Test
    void anEventWithoutTraceContextStillPublishesAndStartsItsOwnTrace() throws Exception {
        UUID jobId = submit();
        jdbc.sql("UPDATE outbox_events SET trace_context = NULL WHERE job_id = :id").param("id", jobId).update();
        spans.reset();

        assertThat(dispatcher.dispatchPending()).isEqualTo(1);

        SpanData wait = span("outbox.wait");
        SpanData publish = span("outbox.publish");
        assertThat(wait.getParentSpanId()).isEqualTo("0000000000000000");
        assertThat(publish.getTraceId()).isEqualTo(wait.getTraceId());
        assertThat(publish.getParentSpanId()).isEqualTo(wait.getSpanId());
        assertThat(rabbit.receive(JobMessagingTopology.QUEUE, 5000)).isNotNull();
    }

    @Test
    void aCorruptTraceContextNeverBlocksThePublication() throws Exception {
        UUID jobId = submit();
        jdbc.sql("UPDATE outbox_events SET trace_context = 'esto-no-es-un-traceparent' WHERE job_id = :id")
                .param("id", jobId).update();
        spans.reset();

        assertThat(dispatcher.dispatchPending()).isEqualTo(1);

        assertThat(rabbit.receive(JobMessagingTopology.QUEUE, 5000)).isNotNull();
        assertThat(span("outbox.publish").getParentSpanId()).isEqualTo(span("outbox.wait").getSpanId());
    }

    @Test
    void anEventWithoutARouteIsRejectedBeforeAnySpanIsOpened() throws Exception {
        UUID jobId = submit();
        // Un tipo de evento sin ruta: el despachador lo rechaza antes de publicar y no abre ningún span de envío.
        jdbc.sql("UPDATE outbox_events SET event_type = 'DESCONOCIDO' WHERE job_id = :id").param("id", jobId).update();
        spans.reset();

        assertThat(dispatcher.dispatchPending()).isZero();

        assertThat(spans.getFinishedSpanItems().stream().map(SpanData::getName))
                .doesNotContain("outbox.publish", "outbox.wait");
    }
}
