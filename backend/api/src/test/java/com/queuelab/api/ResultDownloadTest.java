package com.queuelab.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import com.queuelab.api.support.PostgresTestConfiguration;
import com.queuelab.core.job.Job;
import com.queuelab.core.job.JobRepository;
import com.queuelab.core.job.JobStatus;
import com.queuelab.core.storage.FileStorage;
import com.queuelab.core.storage.StorageArea;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
class ResultDownloadTest {

    private static final String STATS = "{\"rows\":2,\"columns\":[]}";
    private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");

    @Autowired
    MockMvc mvc;

    @Autowired
    JobRepository jobs;

    @Autowired
    FileStorage storage;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM jobs").update();
    }

    private Job insert(String type, JobStatus status) {
        Job queued = Job.queued(UUID.randomUUID(), type, T0);
        jobs.insert(queued);
        if (status == JobStatus.QUEUED) {
            return queued;
        }
        Job running = queued.transitionTo(JobStatus.RUNNING, T0.plusSeconds(1));
        jobs.update(running, JobStatus.QUEUED);
        return switch (status) {
            case RUNNING -> running;
            case COMPLETED -> {
                Job done = running.completed("CSV procesado: 2 filas, 1 columnas", T0.plusSeconds(2));
                jobs.update(done, JobStatus.RUNNING);
                yield done;
            }
            case FAILED -> {
                Job failed = running.failed("Línea 3: comillas sin cerrar", T0.plusSeconds(2));
                jobs.update(failed, JobStatus.RUNNING);
                yield failed;
            }
            default -> throw new IllegalArgumentException(status.name());
        };
    }

    private String attachResult(Job job, String content) {
        String ref = storage.store(StorageArea.RESULT, job.id() + ".stats.json",
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))).reference();
        jobs.attachResult(job.id(), ref);
        return ref;
    }

    @Test
    void completedJobAnnouncesAndServesItsResultFile() throws Exception {
        Job job = insert("csv-import", JobStatus.COMPLETED);
        attachResult(job, STATS);

        mvc.perform(get("/api/v1/jobs/{id}", job.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultFile.name").value(job.id() + ".stats.json"))
                .andExpect(jsonPath("$.resultFile.contentType").value("application/json"))
                .andExpect(jsonPath("$.resultFile.size").value(STATS.length()))
                .andExpect(jsonPath("$.resultFile.downloadUrl").value("/api/v1/jobs/" + job.id() + "/result"));

        mvc.perform(get("/api/v1/jobs/{id}/result", job.id()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/json"))
                .andExpect(header().longValue("Content-Length", STATS.length()))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"" + job.id() + ".stats.json\""))
                .andExpect(content().string(STATS));
    }

    @Test
    void listAnnouncesResultOnlyForJobsThatHaveIt() throws Exception {
        Job withFile = insert("csv-import", JobStatus.COMPLETED);
        attachResult(withFile, STATS);
        Job noop = insert("noop", JobStatus.COMPLETED);

        mvc.perform(get("/api/v1/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id=='" + withFile.id() + "')].resultFile.size")
                        .value(STATS.length()))
                .andExpect(jsonPath("$.items[?(@.id=='" + noop.id() + "' && @.resultFile != null)]").isEmpty());
    }

    @Test
    void queuedAndRunningJobsDoNotAnnounceNorServeAResult() throws Exception {
        for (JobStatus status : new JobStatus[] { JobStatus.QUEUED, JobStatus.RUNNING }) {
            Job job = insert("csv-import", status);

            mvc.perform(get("/api/v1/jobs/{id}", job.id()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.resultFile").doesNotExist());
            mvc.perform(get("/api/v1/jobs/{id}/result", job.id()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.title").value("Resultado no disponible"))
                    .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("todavía")));
        }
    }

    @Test
    void retryingJobDoesNotAnnounceAResultEvenIfAStaleOneExists() throws Exception {
        Job job = insert("csv-import", JobStatus.RUNNING);
        attachResult(job, STATS);
        jdbc.sql("UPDATE jobs SET status = 'RETRYING' WHERE id = :id").param("id", job.id()).update();

        mvc.perform(get("/api/v1/jobs/{id}", job.id()))
                .andExpect(jsonPath("$.resultFile").doesNotExist());
        mvc.perform(get("/api/v1/jobs/{id}/result", job.id()))
                .andExpect(status().isConflict());
    }

    @Test
    void failedJobHasNoResult() throws Exception {
        Job job = insert("csv-import", JobStatus.FAILED);

        mvc.perform(get("/api/v1/jobs/{id}", job.id()))
                .andExpect(jsonPath("$.resultFile").doesNotExist());
        mvc.perform(get("/api/v1/jobs/{id}/result", job.id()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("falló")));
    }

    @Test
    void completedJobWithoutFileIsNotFound() throws Exception {
        Job job = insert("noop", JobStatus.COMPLETED);

        mvc.perform(get("/api/v1/jobs/{id}", job.id()))
                .andExpect(jsonPath("$.resultFile").doesNotExist());
        mvc.perform(get("/api/v1/jobs/{id}/result", job.id()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Resultado no encontrado"));
    }

    @Test
    void resultFileThatVanishedFromStorageIsNotAnnounced() throws Exception {
        Job job = insert("csv-import", JobStatus.COMPLETED);
        String ref = attachResult(job, STATS);
        storage.delete(ref);

        mvc.perform(get("/api/v1/jobs/{id}", job.id()))
                .andExpect(jsonPath("$.resultFile").doesNotExist());
        mvc.perform(get("/api/v1/jobs/{id}/result", job.id()))
                .andExpect(status().isNotFound());
    }

    @Test
    void unknownJobIsNotFound() throws Exception {
        mvc.perform(get("/api/v1/jobs/{id}/result", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Trabajo no encontrado"));
    }
}
