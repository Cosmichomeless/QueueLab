package com.queuelab.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.queuelab.api.support.PostgresTestConfiguration;
import com.queuelab.core.job.JobRepository;

/** Todos los errores comparten formato y ninguno filtra datos internos. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
class ApiErrorsTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    JobRepository jobs;

    @Test
    void malformedIdIsA400WithOurOwnMessage() throws Exception {
        mvc.perform(get("/api/v1/jobs/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Petición no válida"))
                .andExpect(jsonPath("$.detail").value("El valor del parámetro 'id' no tiene un formato válido"));
    }

    @Test
    void unknownStatusFilterIsA400() throws Exception {
        mvc.perform(get("/api/v1/jobs").param("status", "EXPLODED"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("El valor del parámetro 'status' no tiene un formato válido"));
    }

    @Test
    void nonNumericLimitIsA400() throws Exception {
        mvc.perform(get("/api/v1/jobs").param("limit", "many"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("El valor del parámetro 'limit' no tiene un formato válido"));
    }

    @Test
    void unknownRouteAndWrongMethodUseTheSameFormat() throws Exception {
        mvc.perform(get("/api/v1/nope"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        mvc.perform(delete("/api/v1/jobs"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void unexpectedFailureIsAGeneric500WithoutInternals() throws Exception {
        when(jobs.findById(any(UUID.class)))
                .thenThrow(new IllegalStateException("password=hunter2 jdbc:postgresql://db.internal:5432/queuelab"));

        String body = mvc.perform(get("/api/v1/jobs/" + UUID.randomUUID()))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Error interno"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("hunter2", "db.internal", "IllegalStateException", "at com.", "trace");
    }

    @Test
    void failureWhileSubmittingIsAlsoGeneric() throws Exception {
        org.mockito.Mockito.doThrow(new IllegalStateException("connection refused to 10.0.0.5"))
                .when(jobs).insert(any());

        String body = mvc.perform(post("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"noop\"}"))
                .andExpect(status().isInternalServerError())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("10.0.0.5", "connection refused");
    }
}
