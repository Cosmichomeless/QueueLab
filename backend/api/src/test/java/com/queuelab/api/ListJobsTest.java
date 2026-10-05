package com.queuelab.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.queuelab.api.support.PostgresTestConfiguration;
import com.queuelab.core.job.Job;
import com.queuelab.core.job.JobRepository;
import com.queuelab.core.job.JobStatus;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
class ListJobsTest {

    private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");

    @Autowired
    MockMvc mvc;

    @Autowired
    JobRepository jobs;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JsonMapper json;

    @BeforeEach
    void emptyTable() {
        jdbc.update("DELETE FROM jobs");
    }

    /** 25 trabajos; cada 5 comparten created_at para probar el desempate por id. */
    private List<UUID> createJobs() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            Job job = Job.queued(UUID.randomUUID(), "csv-import", T0.plusSeconds(i / 5));
            jobs.insert(job);
            ids.add(job.id());
        }
        return ids;
    }

    private JsonNode fetch(String url) throws Exception {
        var body = mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    @Test
    void successivePagesHaveNoDuplicatesAndCoverEverything() throws Exception {
        List<UUID> created = createJobs();

        List<String> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            JsonNode page = fetch("/api/v1/jobs?limit=10" + (cursor == null ? "" : "&cursor=" + cursor));
            page.get("items").forEach(item -> seen.add(item.get("id").asString()));
            JsonNode next = page.get("nextCursor");
            cursor = next.isNull() ? null : next.asString();
            pages++;
        } while (cursor != null);

        assertThat(pages).isEqualTo(3);
        assertThat(seen).hasSize(25).doesNotHaveDuplicates();
        assertThat(seen).containsExactlyInAnyOrderElementsOf(created.stream().map(UUID::toString).toList());
    }

    @Test
    void orderIsStableNewestFirst() throws Exception {
        createJobs();

        List<String> first = ids(fetch("/api/v1/jobs?limit=100"));
        List<String> second = ids(fetch("/api/v1/jobs?limit=100"));
        assertThat(first).isEqualTo(second);

        var createdAt = new ArrayList<String>();
        fetch("/api/v1/jobs?limit=100").get("items").forEach(i -> createdAt.add(i.get("createdAt").asString()));
        assertThat(createdAt).isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    @Test
    void newJobsCreatedBetweenPagesDoNotCauseDuplicates() throws Exception {
        createJobs();
        JsonNode page1 = fetch("/api/v1/jobs?limit=10");

        // Llegan trabajos nuevos mientras el cliente pagina: aparecen antes de la primera página.
        for (int i = 0; i < 5; i++) {
            jobs.insert(Job.queued(UUID.randomUUID(), "late", T0.plusSeconds(1000 + i)));
        }
        JsonNode page2 = fetch("/api/v1/jobs?limit=10&cursor=" + page1.get("nextCursor").asString());

        List<String> all = new ArrayList<>(ids(page1));
        all.addAll(ids(page2));
        assertThat(all).hasSize(20).doesNotHaveDuplicates();
    }

    @Test
    void filtersByStatus() throws Exception {
        createJobs();
        Job queued = Job.queued(UUID.randomUUID(), "x", T0.plusSeconds(500));
        Job running = queued.transitionTo(JobStatus.RUNNING, T0.plusSeconds(501));
        jobs.insert(Job.queued(running.id(), "x", T0.plusSeconds(500)));
        jobs.update(running, JobStatus.QUEUED);

        JsonNode runningPage = fetch("/api/v1/jobs?status=RUNNING");
        assertThat(ids(runningPage)).containsExactly(running.id().toString());
        runningPage.get("items").forEach(i -> assertThat(i.get("status").asString()).isEqualTo("RUNNING"));

        assertThat(fetch("/api/v1/jobs?status=QUEUED&limit=100").get("items")).hasSize(25);
        assertThat(fetch("/api/v1/jobs?status=FAILED").get("items")).isEmpty();
    }

    @Test
    void lastPageHasNoNextCursor() throws Exception {
        createJobs();

        JsonNode page = fetch("/api/v1/jobs?limit=100");

        assertThat(page.get("items")).hasSize(25);
        assertThat(page.get("nextCursor").isNull()).isTrue();
    }

    @Test
    void invalidParametersReturn400() throws Exception {
        for (String url : List.of(
                "/api/v1/jobs?limit=0",
                "/api/v1/jobs?limit=101",
                "/api/v1/jobs?limit=abc",
                "/api/v1/jobs?status=PAUSED",
                "/api/v1/jobs?cursor=not-a-cursor")) {
            mvc.perform(get(url))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.status").value(400));
        }
    }

    private static List<String> ids(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.get("items").forEach(i -> ids.add(i.get("id").asString()));
        return ids;
    }
}
