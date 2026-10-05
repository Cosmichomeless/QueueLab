package com.queuelab.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import com.queuelab.api.support.PostgresTestConfiguration;
import com.queuelab.core.job.Job;
import com.queuelab.core.job.JobRepository;
import com.queuelab.core.messaging.JobMessageCodec;
import com.queuelab.core.outbox.OutboxEvent;
import com.queuelab.core.outbox.OutboxRepository;

import tools.jackson.databind.json.JsonMapper;

/** Cada trabajo enviado deja su evento de publicación en la misma transacción. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
class OutboxTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JobRepository jobs;

    @MockitoSpyBean
    OutboxRepository outbox;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JsonMapper json;

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM jobs").update(); // el outbox cae en cascada
    }

    private UUID submit() throws Exception {
        String body = mvc.perform(post("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"csv-import\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).get("id").asString());
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    @Test
    void commitLeavesTheJobAndExactlyOnePublishableEvent() throws Exception {
        UUID id = submit();

        var events = outbox.findByJobId(id);
        assertThat(events).hasSize(1);
        OutboxEvent event = events.getFirst();
        assertThat(event.eventType()).isEqualTo(OutboxEvent.JOB_QUEUED);
        assertThat(event.isPublished()).isFalse();
        assertThat(event.attempts()).isZero();
        // El payload guardado cumple el contrato y apunta al trabajo.
        assertThat(JobMessageCodec.decode(JobMessageCodec.encodeJson(event.payload())).jobId()).isEqualTo(id);
        assertThat(event.createdAt()).isEqualTo(jobs.findById(id).orElseThrow().createdAt());
    }

    @Test
    void noQueuedJobIsLeftWithoutAnEvent() throws Exception {
        for (int i = 0; i < 5; i++) {
            submit();
        }

        long orphans = jdbc.sql("""
                SELECT count(*) FROM jobs j
                WHERE j.status = 'QUEUED'
                  AND NOT EXISTS (SELECT 1 FROM outbox_events e WHERE e.job_id = j.id AND e.published_at IS NULL)
                """).query(Long.class).single();
        assertThat(orphans).isZero();
        assertThat(count("jobs")).isEqualTo(5);
        assertThat(count("outbox_events")).isEqualTo(5);
    }

    @Test
    void rollbackLeavesNeitherJobNorEvent() throws Exception {
        // El trabajo ya se insertó cuando falla el outbox: la transacción debe deshacer ambos.
        doThrow(new DataIntegrityViolationException("fallo simulado")).when(outbox).insert(any());

        mvc.perform(post("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"csv-import\"}"))
                .andExpect(status().isInternalServerError());

        assertThat(count("jobs")).isZero();
        assertThat(count("outbox_events")).isZero();
    }

    @Test
    void anEventCannotExistWithoutItsJob() {
        Job ghost = Job.queued(UUID.randomUUID(), "csv-import", Instant.now());

        assertThatThrownBy(() -> outbox.insert(OutboxEvent.jobQueued(ghost, ghost.createdAt())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingAJobRemovesItsEvents() throws Exception {
        UUID id = submit();

        jdbc.sql("DELETE FROM jobs WHERE id = :id").param("id", id).update();

        assertThat(outbox.findByJobId(id)).isEmpty();
        assertThat(jobs.findById(id)).isEmpty();
    }
}
