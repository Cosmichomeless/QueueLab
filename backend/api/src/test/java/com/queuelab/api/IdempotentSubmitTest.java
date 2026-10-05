package com.queuelab.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.queuelab.api.support.PostgresTestConfiguration;
import com.queuelab.core.job.Job;
import com.queuelab.core.job.JobRepository;
import com.queuelab.core.job.JobStatus;

import tools.jackson.databind.json.JsonMapper;

/** Idempotency-Key en POST /api/v1/jobs. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
class IdempotentSubmitTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JobRepository jobs;

    @Autowired
    JsonMapper json;

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM jobs").update(); // el outbox cae en cascada
    }

    private MvcResult submit(String type, String key) throws Exception {
        var request = post("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"" + type + "\"}");
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mvc.perform(request).andReturn();
    }

    private UUID idOf(MvcResult result) throws Exception {
        return UUID.fromString(json.readTree(result.getResponse().getContentAsString()).get("id").asString());
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    @Test
    void firstRequestWithKeyCreatesTheJob() throws Exception {
        MvcResult first = submit("csv-import", "order-1");

        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(first.getResponse().getHeader("Idempotent-Replayed")).isNull();
        assertThat(count("jobs")).isEqualTo(1);
        assertThat(count("outbox_events")).isEqualTo(1);
    }

    @Test
    void sameKeyAndPayloadReturnsTheSameJobWithoutDuplicating() throws Exception {
        MvcResult first = submit("csv-import", "order-1");
        MvcResult second = submit("csv-import", "order-1");

        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        assertThat(second.getResponse().getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(idOf(second)).isEqualTo(idOf(first));
        assertThat(second.getResponse().getHeader("Location")).endsWith("/api/v1/jobs/" + idOf(first));
        assertThat(second.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
        // Ni segundo trabajo ni segundo evento que publicar.
        assertThat(count("jobs")).isEqualTo(1);
        assertThat(count("outbox_events")).isEqualTo(1);
    }

    @Test
    void replayReflectsTheCurrentStateOfTheJob() throws Exception {
        UUID id = idOf(submit("csv-import", "order-1"));
        Job queued = jobs.findById(id).orElseThrow();
        Job running = queued.transitionTo(JobStatus.RUNNING, Instant.now());
        jobs.update(running, JobStatus.QUEUED);
        jobs.update(running.completed("hecho", Instant.now()), JobStatus.RUNNING);

        mvc.perform(post("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"csv-import\"}").header("Idempotency-Key", "order-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.result").value("hecho"));
        assertThat(count("jobs")).isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentPayloadIsAClearConflict() throws Exception {
        submit("csv-import", "order-1");

        mvc.perform(post("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"image-resize\"}").header("Idempotency-Key", "order-1"))
                .andExpect(status().isConflict())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("problem+json")))
                .andExpect(jsonPath("$.title").value("Conflicto de idempotencia"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("clave de idempotencia")));
        assertThat(count("jobs")).isEqualTo(1);
        assertThat(count("outbox_events")).isEqualTo(1);
    }

    @Test
    void differentKeysCreateDifferentJobs() throws Exception {
        UUID a = idOf(submit("csv-import", "key-a"));
        UUID b = idOf(submit("csv-import", "key-b"));

        assertThat(a).isNotEqualTo(b);
        assertThat(count("jobs")).isEqualTo(2);
    }

    @Test
    void withoutKeyEveryRequestCreatesAJob() throws Exception {
        MvcResult first = submit("csv-import", null);
        MvcResult second = submit("csv-import", null);

        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(second.getResponse().getStatus()).isEqualTo(201);
        assertThat(idOf(first)).isNotEqualTo(idOf(second));
        assertThat(count("jobs")).isEqualTo(2);
    }

    @Test
    void invalidPayloadIsRejectedBeforeLookingAtTheKey() throws Exception {
        submit("csv-import", "order-1");

        mvc.perform(post("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"launch-missiles\"}").header("Idempotency-Key", "order-1"))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "con espacios", "tab\there", "ñandú"})
    void malformedKeysAreRejected(String key) throws Exception {
        MvcResult result = submit("csv-import", key);

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString()).contains("Idempotency-Key");
        assertThat(count("jobs")).isZero();
    }

    @Test
    void tooLongKeyIsRejectedAndMaximumLengthAccepted() throws Exception {
        assertThat(submit("csv-import", "k".repeat(256)).getResponse().getStatus()).isEqualTo(400);
        assertThat(submit("csv-import", "k".repeat(255)).getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void simultaneousRequestsWithTheSameKeyCreateExactlyOneJob() throws Exception {
        int requests = 12;
        var pool = Executors.newFixedThreadPool(requests);
        try {
            List<Callable<MvcResult>> calls = IntStream.range(0, requests)
                    .<Callable<MvcResult>>mapToObj(i -> () -> submit("csv-import", "race")).toList();
            List<MvcResult> results = new ArrayList<>();
            for (Future<MvcResult> future : pool.invokeAll(calls)) {
                results.add(future.get());
            }

            Set<UUID> ids = results.stream().map(r -> {
                try {
                    return idOf(r);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }).collect(Collectors.toSet());
            assertThat(ids).hasSize(1);
            assertThat(results.stream().filter(r -> r.getResponse().getStatus() == 201)).hasSize(1);
            assertThat(results.stream().filter(r -> r.getResponse().getStatus() == 200)).hasSize(requests - 1);
            assertThat(count("jobs")).isEqualTo(1);
            assertThat(count("outbox_events")).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void databaseRejectsAKeyWithoutFingerprint() {
        UUID id = UUID.randomUUID();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql(
                        "INSERT INTO jobs (id, type, status, created_at, updated_at, idempotency_key) "
                                + "VALUES (:id, 'noop', 'QUEUED', now(), now(), 'solo-clave')")
                .param("id", id).update())
                .hasMessageContaining("jobs_idempotency_pair_chk");
    }
}
