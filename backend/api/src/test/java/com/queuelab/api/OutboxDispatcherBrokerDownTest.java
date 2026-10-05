package com.queuelab.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import com.queuelab.api.outbox.OutboxDispatcher;
import com.queuelab.api.support.PostgresTestConfiguration;
import com.queuelab.core.outbox.OutboxRepository;

import tools.jackson.databind.json.JsonMapper;

/** RabbitMQ realmente inaccesible (puerto cerrado): la API sigue aceptando trabajos y el outbox los conserva. */
@SpringBootTest(properties = {"spring.rabbitmq.host=localhost", "spring.rabbitmq.port=1"})
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
class OutboxDispatcherBrokerDownTest {

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

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM jobs").update();
    }

    @Test
    void jobsAreAcceptedAndKeptPendingWhileTheBrokerIsDown() throws Exception {
        var response = mvc.perform(post("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"noop\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID jobId = UUID.fromString(json.readTree(response).get("id").asString());

        assertThat(dispatcher.dispatchPending()).isZero();
        assertThat(dispatcher.dispatchPending()).isZero();

        var event = outbox.findByJobId(jobId).getFirst();
        assertThat(event.isPublished()).isFalse();
        assertThat(event.attempts()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT last_error FROM outbox_events WHERE id = :id").param("id", event.id())
                .query(String.class).single()).startsWith("RabbitMQ no disponible");
    }
}
