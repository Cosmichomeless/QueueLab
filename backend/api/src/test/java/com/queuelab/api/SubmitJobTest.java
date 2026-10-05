package com.queuelab.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
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
class SubmitJobTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JobRepository jobs;

    @Autowired
    JsonMapper json;

    @Test
    void validRequestCreatesQueuedJobAndReturnsItsId() throws Exception {
        var response = mvc.perform(post("/api/v1/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"csv-import\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.type").value("csv-import"))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.startedAt").doesNotExist())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("/api/v1/jobs/")))
                .andReturn().getResponse().getContentAsString();

        JsonNode body = json.readTree(response);
        UUID id = UUID.fromString(body.get("id").asString());

        Job stored = jobs.findById(id).orElseThrow();
        assertThat(stored.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(stored.type()).isEqualTo("csv-import");
    }

    @Test
    void responseDoesNotWaitForProcessing() throws Exception {
        // Nada procesa el trabajo durante la petición: al responder sigue en QUEUED, sin inicio ni fin.
        var response = mvc.perform(post("/api/v1/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"noop\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        UUID id = UUID.fromString(json.readTree(response).get("id").asString());
        Job stored = jobs.findById(id).orElseThrow();

        assertThat(stored.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(stored.startedAt()).isNull();
        assertThat(stored.finishedAt()).isNull();
    }

    @Test
    void eachSubmissionGetsItsOwnId() throws Exception {
        String first = submit("noop");
        String second = submit("noop");

        assertThat(first).isNotEqualTo(second);
    }

    private String submit(String type) throws Exception {
        var response = mvc.perform(post("/api/v1/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"" + type + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("id").asString();
    }
}
