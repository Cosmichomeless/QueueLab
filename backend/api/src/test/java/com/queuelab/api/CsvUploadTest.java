package com.queuelab.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.queuelab.api.support.PostgresTestConfiguration;
import com.queuelab.core.job.JobRepository;
import com.queuelab.core.outbox.OutboxRepository;
import com.queuelab.core.storage.FileStorage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/** POST /api/v1/jobs/csv: subida validada, fichero guardado, referencia en la base de datos y trabajo encolado. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
class CsvUploadTest {

    static final String CSV = "name,age\nana,30\nluis,41\n";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JobRepository jobs;

    @Autowired
    FileStorage storage;

    @MockitoSpyBean
    OutboxRepository outbox;

    @Value("${queuelab.storage.directory}")
    Path storageDirectory;

    @BeforeEach
    void clean() throws IOException {
        jdbc.sql("DELETE FROM jobs").update(); // el outbox cae en cascada
        try (Stream<Path> files = Files.list(inputs())) {
            files.forEach(f -> f.toFile().delete());
        }
    }

    private Path inputs() throws IOException {
        return Files.createDirectories(storageDirectory.toAbsolutePath().resolve("inputs"));
    }

    private List<Path> storedInputs() throws IOException {
        try (Stream<Path> files = Files.list(inputs())) {
            return files.toList();
        }
    }

    private ResultActions upload(String filename, String contentType, byte[] bytes) throws Exception {
        return mvc.perform(multipart("/api/v1/jobs/csv").file(new MockMultipartFile("file", filename, contentType, bytes)));
    }

    private ResultActions upload(String filename, String contentType, String text) throws Exception {
        return upload(filename, contentType, text.getBytes(StandardCharsets.UTF_8));
    }

    private int jobCount() {
        return jdbc.sql("SELECT count(*) FROM jobs").query(Integer.class).single();
    }

    private void assertNothingWasCreated() throws IOException {
        assertThat(jobCount()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM outbox_events").query(Integer.class).single()).isZero();
        assertThat(storedInputs()).isEmpty();
    }

    @Test
    void validCsvIsStoredAndQueuedWithOnlyAReferenceInTheDatabase() throws Exception {
        String body = upload("people.csv", "text/csv", CSV)
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.matchesPattern(".*/api/v1/jobs/[0-9a-f-]{36}")))
                .andExpect(jsonPath("$.type").value("csv-import"))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.attempts").value(0))
                .andReturn().getResponse().getContentAsString();

        UUID id = UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.id"));
        String reference = "inputs/" + id + ".csv";
        assertThat(jobs.findInputRef(id)).contains(reference);
        assertThat(storage.exists(reference)).isTrue();
        try (var in = storage.open(reference)) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(CSV);
        }
        assertThat(storedInputs()).hasSize(1);
        assertThat(jdbc.sql("SELECT count(*) FROM outbox_events WHERE job_id = :id AND event_type = 'JOB_QUEUED' AND published_at IS NULL")
                .param("id", id).query(Integer.class).single()).isEqualTo(1);
        // Y el trabajo es consultable como cualquier otro.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/jobs/" + id))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("QUEUED"));
    }

    @Test
    void theClientFileNameIsNeverUsedToStoreTheFile() throws Exception {
        upload("../../etc/evil.csv", "text/csv", CSV).andExpect(status().isCreated());

        assertThat(storedInputs()).singleElement().satisfies(file ->
                assertThat(file.getFileName().toString()).matches("[0-9a-f-]{36}\\.csv"));
        assertThat(storageDirectory.toAbsolutePath().resolveSibling("evil.csv")).doesNotExist();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("acceptedUploads")
    void acceptsAnythingDeclaredAsCsvAndValidUtf8(String description, String filename, String contentType, byte[] bytes)
            throws Exception {
        upload(filename, contentType, bytes).andExpect(status().isCreated());

        assertThat(storedInputs()).hasSize(1);
        assertThat(jobCount()).isEqualTo(1);
    }

    static Stream<Arguments> acceptedUploads() {
        byte[] bom = new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] withBom = concat(bom, "a,b\n1,2\n".getBytes(StandardCharsets.UTF_8));
        return Stream.of(
                Arguments.of("solo cabecera", "h.csv", "text/csv", "a,b".getBytes(StandardCharsets.UTF_8)),
                Arguments.of("CRLF", "h.csv", "text/csv", "a,b\r\n1,2\r\n".getBytes(StandardCharsets.UTF_8)),
                Arguments.of("BOM inicial", "h.csv", "text/csv", withBom),
                Arguments.of("charset en el Content-Type", "h.csv", "text/csv; charset=utf-8", "a\n1\n".getBytes(StandardCharsets.UTF_8)),
                Arguments.of("octet-stream pero extensión .CSV", "DATA.CSV", "application/octet-stream", "a\n1\n".getBytes(StandardCharsets.UTF_8)),
                Arguments.of("Excel en Windows (vnd.ms-excel) con .csv", "x.csv", "application/vnd.ms-excel", "a\n1\n".getBytes(StandardCharsets.UTF_8)),
                Arguments.of("text/csv con otra extensión", "datos.txt", "text/csv", "a\n1\n".getBytes(StandardCharsets.UTF_8)),
                Arguments.of("UTF-8 con tildes", "h.csv", "text/csv", "nombre,año\nJosé,2024\n".getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    @Test
    void aFileThatIsNotDeclaredAsCsvIsRejectedWith415() throws Exception {
        upload("notes.txt", "text/plain", CSV)
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Solo se admiten ficheros CSV (Content-Type text/csv o extensión .csv)"));

        assertNothingWasCreated();
    }

    @Test
    void anUnparsableContentTypeFallsBackToTheExtension() throws Exception {
        upload("x.csv", "esto/no/es/valido", CSV).andExpect(status().isCreated());
        upload("x.bin", "esto/no/es/valido", CSV).andExpect(status().isUnsupportedMediaType());

        assertThat(jobCount()).isEqualTo(1);
    }

    @Test
    void anEmptyFileIsRejectedWith400() throws Exception {
        upload("empty.csv", "text/csv", new byte[0])
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("El fichero está vacío: debe tener al menos la cabecera"));

        assertNothingWasCreated();
    }

    @Test
    void aBlankHeaderLineIsRejectedWith400() throws Exception {
        upload("x.csv", "text/csv", "\n1,2\n").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("La primera línea (cabecera) está vacía"));
        upload("x.csv", "text/csv", "   \r\n1,2\r\n").andExpect(status().isBadRequest());
        upload("x.csv", "text/csv", "\n").andExpect(status().isBadRequest());

        assertNothingWasCreated();
    }

    @Test
    void aFileThatIsNotUtf8IsRejectedWith400() throws Exception {
        // «año» en ISO-8859-1: el byte 0xF1 no es UTF-8 válido.
        upload("latin1.csv", "text/csv", "nombre,año\nJosé,2024\n".getBytes(StandardCharsets.ISO_8859_1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("El fichero no es UTF-8 válido"));

        assertNothingWasCreated();
    }

    @Test
    void anAbsurdlyLongHeaderLineIsRejectedWith400() throws Exception {
        upload("long.csv", "text/csv", "a".repeat(70_000) + "\n1\n")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("La primera línea (cabecera) es demasiado larga"));

        assertNothingWasCreated();
    }

    @Test
    void aMissingFilePartIsRejectedWith400() throws Exception {
        mvc.perform(multipart("/api/v1/jobs/csv").file(new MockMultipartFile("otro", "x.csv", "text/csv", CSV.getBytes())))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        assertNothingWasCreated();
    }

    @Test
    void aRequestThatIsNotMultipartIsRejectedWith415() throws Exception {
        mvc.perform(post("/api/v1/jobs/csv").contentType("text/csv").content(CSV))
                .andExpect(status().isUnsupportedMediaType());

        assertNothingWasCreated();
    }

    @Test
    void ifCreatingTheJobFailsAfterStoringTheFileItIsRemoved() throws Exception {
        doThrow(new IllegalStateException("outbox caído")).when(outbox).insert(any());

        upload("x.csv", "text/csv", CSV).andExpect(status().isInternalServerError());

        // La transacción se deshizo (ni trabajo ni evento) y el fichero guardado se borró.
        assertNothingWasCreated();
    }
}
