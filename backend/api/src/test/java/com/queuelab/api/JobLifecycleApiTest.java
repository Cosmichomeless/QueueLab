package com.queuelab.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import com.queuelab.api.support.PostgresTestConfiguration;
import com.queuelab.core.job.InvalidJobTransitionException;
import com.queuelab.core.job.Job;
import com.queuelab.core.job.JobRepository;
import com.queuelab.core.job.JobStatus;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Ciclo de vida de los trabajos de extremo a extremo (API + repositorio) contra un PostgreSQL real
 * cuyo esquema crea Flyway al arrancar el contexto.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
class JobLifecycleApiTest {

    private static final Instant T0 = Instant.parse("2026-03-01T08:00:00Z");

    @Autowired
    MockMvc mvc;

    @Autowired
    JobRepository jobs;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JsonMapper json;

    @BeforeEach
    void cleanJobs() {
        jdbc.sql("DELETE FROM jobs").update();
    }

    @Test
    void schemaComesFromFlywayMigrationsAppliedToPostgres() {
        List<String> applied = jdbc.sql("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank")
                .query(String.class).list();
        String database = jdbc.sql("SELECT version()").query(String.class).single();

        assertThat(applied).contains("1", "2", "3");
        assertThat(database).startsWith("PostgreSQL");
        assertThat(jdbc.sql("SELECT count(*) FROM information_schema.tables WHERE table_name = 'jobs'")
                .query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void jobGoesThroughItsWholeLifecycleAndTheApiReflectsEachStep() throws Exception {
        String created = mvc.perform(post("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"csv-import\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(json.readTree(created).get("id").asString());

        Job queued = jobs.findById(id).orElseThrow();
        Job running = queued.transitionTo(JobStatus.RUNNING, queued.createdAt().plusSeconds(1));
        assertThat(jobs.update(running, JobStatus.QUEUED)).isTrue();
        mvc.perform(get("/api/v1/jobs/{id}", id))
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.startedAt").isNotEmpty())
                .andExpect(jsonPath("$.finishedAt").doesNotExist());

        Job retrying = running.transitionTo(JobStatus.RETRYING, running.updatedAt().plusSeconds(1));
        assertThat(jobs.update(retrying, JobStatus.RUNNING)).isTrue();
        Job again = retrying.transitionTo(JobStatus.RUNNING, retrying.updatedAt().plusSeconds(1));
        assertThat(jobs.update(again, JobStatus.RETRYING)).isTrue();
        Job completed = again.transitionTo(JobStatus.COMPLETED, again.updatedAt().plusSeconds(1));
        assertThat(jobs.update(completed, JobStatus.RUNNING)).isTrue();

        mvc.perform(get("/api/v1/jobs/{id}", id))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                // started_at conserva el primer arranque aunque hubo un reintento
                .andExpect(jsonPath("$.startedAt").value(running.startedAt().toString()))
                .andExpect(jsonPath("$.finishedAt").isNotEmpty());
    }

    @Test
    void paginationWalksEveryJobExactlyOnceAndFiltersByStatus() throws Exception {
        List<UUID> expected = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            Job job = Job.queued(UUID.randomUUID(), "noop", T0.plusSeconds(i));
            jobs.insert(job);
            if (i % 2 == 0) {
                jobs.update(job.transitionTo(JobStatus.RUNNING, T0.plusSeconds(100)), JobStatus.QUEUED);
            }
            expected.add(0, job.id()); // más reciente primero
        }

        List<UUID> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            var request = get("/api/v1/jobs").param("limit", "3");
            if (cursor != null) {
                request.param("cursor", cursor);
            }
            JsonNode page = json.readTree(mvc.perform(request).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString());
            page.get("items").forEach(item -> seen.add(UUID.fromString(item.get("id").asString())));
            cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asString();
            pages++;
        } while (cursor != null);

        assertThat(pages).isEqualTo(3);
        assertThat(seen).containsExactlyElementsOf(expected);

        mvc.perform(get("/api/v1/jobs").param("status", "RUNNING"))
                .andExpect(jsonPath("$.items.length()").value(4))
                .andExpect(jsonPath("$.items[*].status").value(org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.is("RUNNING"))));
    }

    @ParameterizedTest
    @CsvSource({
            "QUEUED,COMPLETED", "QUEUED,FAILED", "QUEUED,RETRYING", "QUEUED,QUEUED",
            "COMPLETED,RUNNING", "COMPLETED,QUEUED", "FAILED,RUNNING", "FAILED,RETRYING",
            "RETRYING,COMPLETED", "RETRYING,FAILED"
    })
    void invalidTransitionIsRejectedAndLeavesTheStoredJobUntouched(JobStatus from, JobStatus to) throws Exception {
        Job job = persistedIn(from);

        assertThatThrownBy(() -> job.transitionTo(to, T0.plusSeconds(500)))
                .isInstanceOf(InvalidJobTransitionException.class);

        mvc.perform(get("/api/v1/jobs/{id}", job.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(from.name()));
    }

    @Test
    void concurrentWriterCannotOverwriteAJobThatAlreadyMoved() {
        Job queued = Job.queued(UUID.randomUUID(), "noop", T0);
        jobs.insert(queued);
        Job running = queued.transitionTo(JobStatus.RUNNING, T0.plusSeconds(1));
        assertThat(jobs.update(running, JobStatus.QUEUED)).isTrue();

        Job staleView = queued.transitionTo(JobStatus.RUNNING, T0.plusSeconds(2));
        assertThat(jobs.update(staleView, JobStatus.QUEUED)).isFalse();
        assertThat(jobs.findById(queued.id()).orElseThrow().updatedAt()).isEqualTo(running.updatedAt());
    }

    @Test
    void unknownJobIs404AndMalformedInputsAre400WithTheSameProblemFormat() throws Exception {
        mvc.perform(get("/api/v1/jobs/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404));

        for (String path : List.of(
                "/api/v1/jobs/not-a-uuid",
                "/api/v1/jobs?status=EXPLODED",
                "/api/v1/jobs?limit=0",
                "/api/v1/jobs?limit=101",
                "/api/v1/jobs?limit=abc",
                "/api/v1/jobs?cursor=%25%25%25")) {
            mvc.perform(get(java.net.URI.create(path)))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.title").value("Petición no válida"));
        }
    }

    @Test
    void invalidSubmissionsCreateNothing() throws Exception {
        for (String body : List.of("{}", "{\"type\":\"bogus\"}", "{\"type\":", "")) {
            mvc.perform(post("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }

        assertThat(jdbc.sql("SELECT count(*) FROM jobs").query(Long.class).single()).isZero();
    }

    /** Guarda un trabajo recorriendo el camino válido más corto hasta {@code target}. */
    private Job persistedIn(JobStatus target) {
        Job job = Job.queued(UUID.randomUUID(), "noop", T0);
        jobs.insert(job);
        List<JobStatus> path = switch (target) {
            case QUEUED -> List.of();
            case RUNNING -> List.of(JobStatus.RUNNING);
            case RETRYING -> List.of(JobStatus.RUNNING, JobStatus.RETRYING);
            case COMPLETED -> List.of(JobStatus.RUNNING, JobStatus.COMPLETED);
            case FAILED -> List.of(JobStatus.RUNNING, JobStatus.FAILED);
        };
        long seconds = 1;
        for (JobStatus next : path) {
            Job moved = job.transitionTo(next, T0.plusSeconds(seconds++));
            assertThat(jobs.update(moved, job.status())).isTrue();
            job = moved;
        }
        return job;
    }
}
