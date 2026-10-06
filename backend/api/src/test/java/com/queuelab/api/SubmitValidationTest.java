package com.queuelab.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.queuelab.api.support.PostgresTestConfiguration;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
class SubmitValidationTest {

    @Autowired
    MockMvc mvc;

    private ResultActions submit(String body) throws Exception {
        return mvc.perform(post("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"image-resize", "file-conversion", "batch-analysis"})
    void typesThatAreNotImplementedAreRejected(String type) throws Exception {
        submit("{\"type\":\"" + type + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        "Tipo de trabajo desconocido. Tipos admitidos: csv-import, noop"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"type\":null}", "{\"type\":\"\"}", "{\"type\":\"   \"}"})
    void missingOrBlankTypeIsRejected(String body) throws Exception {
        submit(body)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Petición no válida"))
                .andExpect(jsonPath("$.detail").value("El campo 'type' es obligatorio"));
    }

    @Test
    void unknownTypeIsRejectedListingTheSupportedOnes() throws Exception {
        submit("{\"type\":\"launch-missiles\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        "Tipo de trabajo desconocido. Tipos admitidos: csv-import, noop"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CSV-Import", "csv_import", "csv import", "-csv", "csv-", "<script>"})
    void badlyFormattedTypeIsRejected(String type) throws Exception {
        submit("{\"type\":\"" + type + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("solo admite")));
    }

    @Test
    void overlongTypeIsRejected() throws Exception {
        submit("{\"type\":\"" + "a".repeat(101) + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("El campo 'type' no puede superar 100 caracteres"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not json", "{\"type\":", "[]", "null", "{\"type\":{\"a\":1}}"})
    void unreadableBodyIsRejectedWithoutEchoingParserDetails(String body) throws Exception {
        String response = submit(body)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain("Exception", "com.queuelab", "jackson", "tools.jackson");
    }

    @Test
    void unsupportedContentTypeIsA4xxProblem() throws Exception {
        mvc.perform(post("/api/v1/jobs").contentType(MediaType.TEXT_PLAIN).content("noop"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }
}
