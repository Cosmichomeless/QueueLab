package com.queuelab.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import com.queuelab.api.support.PostgresTestConfiguration;

/** El tamaño máximo (aquí 1 KiB) se rechaza con 413 antes de guardar nada. */
@SpringBootTest(properties = "queuelab.csv.max-file-size=1KB")
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
class CsvUploadLimitTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Value("${queuelab.storage.directory}")
    Path storageDirectory;

    @BeforeEach
    void clean() throws Exception {
        jdbc.sql("DELETE FROM jobs").update();
        Path inputs = Files.createDirectories(storageDirectory.toAbsolutePath().resolve("inputs"));
        try (Stream<Path> files = Files.list(inputs)) {
            files.forEach(f -> f.toFile().delete());
        }
    }

    private static byte[] csvOfSize(int bytes) {
        StringBuilder sb = new StringBuilder("a\n");
        while (sb.length() < bytes) {
            sb.append("1\n");
        }
        return sb.substring(0, bytes).getBytes();
    }

    @Test
    void aFileOverTheLimitIsRejectedWith413AndLeavesNothingBehind() throws Exception {
        mvc.perform(multipart("/api/v1/jobs/csv").file(new MockMultipartFile("file", "big.csv", "text/csv", csvOfSize(1025))))
                .andExpect(status().isContentTooLarge())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("El fichero supera el tamaño máximo permitido de 1024 bytes"));

        assertThat(jdbc.sql("SELECT count(*) FROM jobs").query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM outbox_events").query(Integer.class).single()).isZero();
        try (Stream<Path> files = Files.list(storageDirectory.toAbsolutePath().resolve("inputs"))) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void aFileExactlyAtTheLimitIsAccepted() throws Exception {
        mvc.perform(multipart("/api/v1/jobs/csv").file(new MockMultipartFile("file", "ok.csv", "text/csv", csvOfSize(1024))))
                .andExpect(status().isCreated());
    }
}
