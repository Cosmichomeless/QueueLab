package com.queuelab.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import com.queuelab.api.outbox.OutboxDispatcher;
import com.queuelab.api.support.PostgresTestConfiguration;
import com.queuelab.api.support.RabbitTestConfiguration;
import com.queuelab.core.messaging.JobMessageCodec;
import com.queuelab.core.messaging.JobMessagingTopology;
import com.queuelab.core.outbox.OutboxEvent;
import com.queuelab.core.outbox.OutboxRepository;

import tools.jackson.databind.json.JsonMapper;

/** El despachador publica contra un RabbitMQ real y solo marca como publicado lo que el broker confirma. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestConfiguration.class, RabbitTestConfiguration.class})
class OutboxDispatcherTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    OutboxDispatcher dispatcher;

    @Autowired
    OutboxRepository outbox;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JsonMapper json;

    @Autowired
    RabbitAdmin admin;

    @MockitoSpyBean
    RabbitTemplate rabbit;

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM jobs").update();
        admin.initialize();
        admin.purgeQueue(JobMessagingTopology.QUEUE);
        admin.purgeQueue(JobMessagingTopology.DEAD_LETTER_QUEUE);
    }

    private UUID submit() throws Exception {
        var response = mvc.perform(post("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"noop\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(response).get("id").asString());
    }

    private OutboxEvent eventOf(UUID jobId) {
        return outbox.findByJobId(jobId).getFirst();
    }

    @Test
    void delayedEventIsNotPublishedUntilItsAvailableAtPasses() throws Exception {
        UUID jobId = submit();
        // El evento inicial se publica; luego programamos un reintento en el futuro y otro ya vencido.
        assertThat(dispatcher.dispatchPending()).isEqualTo(1);
        var job = new com.queuelab.core.job.JobRepository(jdbc).findById(jobId).orElseThrow();
        Instant now = Instant.now();
        outbox.insert(OutboxEvent.jobQueued(job, now), now.plusSeconds(3600));
        assertThat(dispatcher.dispatchPending()).isZero();

        outbox.insert(OutboxEvent.jobQueued(job, now), now.minusSeconds(1));
        assertThat(dispatcher.dispatchPending()).isEqualTo(1);

        List<OutboxEvent> events = outbox.findByJobId(jobId);
        assertThat(events).hasSize(3);
        assertThat(events.stream().filter(OutboxEvent::isPublished)).hasSize(2);
        // El que sigue pendiente es justo el de espera futura.
        assertThat(jdbc.sql("SELECT count(*) FROM outbox_events WHERE job_id = :id AND published_at IS NULL "
                + "AND available_at > now()").param("id", jobId).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void deadLetteredEventGoesToTheDeadLetterQueueAndNotToTheMainOne() throws Exception {
        UUID jobId = submit();
        assertThat(dispatcher.dispatchPending()).isEqualTo(1);
        admin.purgeQueue(JobMessagingTopology.QUEUE);
        var repo = new com.queuelab.core.job.JobRepository(jdbc);
        var running = repo.claim(jobId, Instant.now(), Instant.now().plusSeconds(60)).orElseThrow();
        var failed = running.failed("El servicio externo no responde", Instant.now());
        repo.finishAttempt(failed);
        outbox.insert(OutboxEvent.deadLettered(failed, Instant.now()));

        assertThat(dispatcher.dispatchPending()).isEqualTo(1);

        Message dead = rabbit.receive(JobMessagingTopology.DEAD_LETTER_QUEUE, 5_000);
        assertThat(dead).isNotNull();
        var body = json.readTree(dead.getBody());
        assertThat(body.get("jobId").asString()).isEqualTo(jobId.toString());
        assertThat(body.get("attempts").asInt()).isEqualTo(1);
        assertThat(body.get("cause").asString()).isEqualTo("El servicio externo no responde");
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("version", "jobId", "attempts", "cause");
        assertThat(rabbit.receive(JobMessagingTopology.QUEUE, 300)).isNull();
    }

    @Test
    void unknownEventTypeIsRejectedAndStaysPending() throws Exception {
        UUID jobId = submit();
        assertThat(dispatcher.dispatchPending()).isEqualTo(1);
        jdbc.sql("UPDATE outbox_events SET event_type = 'RARO', published_at = NULL WHERE job_id = :id")
                .param("id", jobId).update();

        assertThat(dispatcher.dispatchPending()).isZero();

        assertThat(eventOf(jobId).isPublished()).isFalse();
        assertThat(eventOf(jobId).attempts()).isEqualTo(1);
    }

    @Test
    void confirmedEventIsMarkedPublishedAndReachesTheQueue() throws Exception {
        UUID jobId = submit();
        assertThat(eventOf(jobId).isPublished()).isFalse();

        assertThat(dispatcher.dispatchPending()).isEqualTo(1);

        OutboxEvent event = eventOf(jobId);
        assertThat(event.isPublished()).isTrue();
        assertThat(event.attempts()).isZero();
        Message received = rabbit.receive(JobMessagingTopology.QUEUE, 5_000);
        assertThat(received).isNotNull();
        assertThat(JobMessageCodec.decode(received).jobId()).isEqualTo(jobId);
        assertThat(received.getMessageProperties().getContentType()).isEqualTo("application/json");
    }

    @Test
    void publishedEventIsNotSentAgain() throws Exception {
        submit();
        assertThat(dispatcher.dispatchPending()).isEqualTo(1);
        assertThat(dispatcher.dispatchPending()).isZero();

        assertThat(rabbit.receive(JobMessagingTopology.QUEUE, 5_000)).isNotNull();
        assertThat(rabbit.receive(JobMessagingTopology.QUEUE, 500)).isNull();
    }

    @Test
    void severalPendingEventsAreAllPublished() throws Exception {
        submit();
        submit();
        submit();

        assertThat(dispatcher.dispatchPending()).isEqualTo(3);
        assertThat(jdbc.sql("SELECT count(*) FROM outbox_events WHERE published_at IS NULL")
                .query(Long.class).single()).isZero();
    }

    @Test
    void brokerFailureLeavesTheEventPendingAndItIsRetriedLater() throws Exception {
        UUID jobId = submit();
        doThrow(new AmqpConnectException(new java.io.IOException("caído")))
                .when(rabbit).send(any(String.class), any(String.class), any(Message.class),
                        any(org.springframework.amqp.rabbit.connection.CorrelationData.class));

        assertThat(dispatcher.dispatchPending()).isZero();

        OutboxEvent failed = eventOf(jobId);
        assertThat(failed.isPublished()).isFalse();
        assertThat(failed.attempts()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT last_error FROM outbox_events WHERE id = :id").param("id", failed.id())
                .query(String.class).single()).contains("RabbitMQ no disponible");

        // El broker vuelve: el mismo evento se publica en la siguiente pasada.
        org.mockito.Mockito.reset(rabbit);
        assertThat(dispatcher.dispatchPending()).isEqualTo(1);
        assertThat(eventOf(jobId).isPublished()).isTrue();
        assertThat(jdbc.sql("SELECT last_error FROM outbox_events WHERE id = :id").param("id", failed.id())
                .query(String.class).optional()).isEmpty();
    }

    @Test
    void unroutableMessageIsNotMarkedPublished() throws Exception {
        UUID jobId = submit();
        admin.deleteQueue(JobMessagingTopology.QUEUE); // el exchange queda sin cola a la que enrutar

        assertThat(dispatcher.dispatchPending()).isZero();

        OutboxEvent event = eventOf(jobId);
        assertThat(event.isPublished()).isFalse();
        assertThat(event.attempts()).isEqualTo(1);
    }
}
