package com.queuelab.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.queuelab.api.support.PostgresTestConfiguration;

/**
 * Revisión de seguridad de la subida (#51): nombres peligrosos, rechazo de lo que no es CSV, ausencia de
 * contenido y nombres del cliente en respuestas, base de datos y logs, y endpoints que no deben existir.
 * Complementa {@code CsvUploadTest} (formato) y {@code CsvUploadLimitTest} (tamaño).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class UploadSecurityTest {

    /** Marcas que no deben aparecer en ningún sitio: se buscan en respuestas, base de datos y logs. */
    static final String NAME_MARK = "NOMBRE-SECRETO-7c1d";
    static final String CELL_MARK = "CELDA-SECRETA-93ab";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Value("${queuelab.storage.directory}")
    Path storageDirectory;

    @BeforeEach
    void clean() throws IOException {
        jdbc.sql("DELETE FROM jobs").update();
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

    private MvcResult upload(String filename, String contentType, byte[] bytes) throws Exception {
        return mvc.perform(multipart("/api/v1/jobs/csv").file(new MockMultipartFile("file", filename, contentType, bytes)))
                .andReturn();
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    // --- nombres peligrosos ---

    @ParameterizedTest(name = "[{index}] se acepta y se guarda con un nombre propio: {0}")
    @ValueSource(strings = {
            "../../etc/passwd.csv",
            "..\\..\\Windows\\win.ini.csv",
            "/etc/passwd.csv",
            "C:\\Windows\\System32\\config\\SAM.csv",
            "....//....//x.csv",
            "%2e%2e%2f%2e%2e%2fx.csv",
            "con.csv",
            "nul\u0000.csv",
            "linea\nnueva.csv",
            "<script>alert(1)</script>.csv",
            "'; DROP TABLE jobs; --.csv",
            "\u202Egnp.csv",
            ".csv"
    })
    void aDangerousClientNameNeverReachesTheFileSystem(String filename) throws Exception {
        MvcResult result = upload(filename, "text/csv", utf8("a,b\n1,2\n"));

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(storedInputs()).singleElement().satisfies(file ->
                assertThat(file.getFileName().toString()).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.csv"));
        // Nada se escribió fuera de la zona de entradas, que es lo que un nombre con «..» intentaría.
        try (Stream<Path> siblings = Files.list(storageDirectory.toAbsolutePath())) {
            assertThat(siblings.map(p -> p.getFileName().toString())).doesNotContain("passwd.csv", "win.ini.csv", "x.csv");
        }
        assertThat(result.getResponse().getContentAsString()).doesNotContain("passwd", "win.ini", "script", "DROP");
    }

    @Test
    void aVeryLongClientNameIsHarmless() throws Exception {
        MvcResult result = upload("a".repeat(5000) + ".csv", "text/csv", utf8("a\n1\n"));

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(storedInputs()).hasSize(1);
    }

    @ParameterizedTest(name = "[{index}] se rechaza con 415: {0}")
    @ValueSource(strings = {"shell.sh", "payload.csv.exe", "x.csv\u0000.exe", "datos.csv ", "csv", "", "../.csv/x.html"})
    void aNameThatIsNotCsvWithAGenericContentTypeIsRejected(String filename) throws Exception {
        MvcResult result = upload(filename, "application/octet-stream", utf8("a\n1\n"));

        assertThat(result.getResponse().getStatus()).isEqualTo(415);
        assertThat(storedInputs()).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM jobs").query(Integer.class).single()).isZero();
    }

    @ParameterizedTest(name = "[{index}] un Content-Type de ejecutable o web no es CSV: {0}")
    @ValueSource(strings = {"application/x-msdownload", "text/html", "application/javascript", "image/svg+xml", "application/zip"})
    void aNonCsvContentTypeWithoutCsvExtensionIsRejected(String contentType) throws Exception {
        assertThat(upload("payload.bin", contentType, utf8("a\n1\n")).getResponse().getStatus()).isEqualTo(415);
        assertThat(storedInputs()).isEmpty();
    }

    // --- el contenido y el nombre del cliente no se filtran ---

    @Test
    void neitherTheNameNorTheContentOfAnUploadAppearInTheResponseTheDatabaseOrTheLogs(CapturedOutput output)
            throws Exception {
        MvcResult accepted = upload(NAME_MARK + ".csv", "text/csv", utf8("col\n" + CELL_MARK + "\n"));
        assertThat(accepted.getResponse().getStatus()).isEqualTo(201);

        assertThat(accepted.getResponse().getContentAsString()).doesNotContain(NAME_MARK, CELL_MARK);
        assertThat(jdbc.sql("SELECT count(*) FROM jobs WHERE jobs::text LIKE :a OR jobs::text LIKE :b")
                .param("a", "%" + NAME_MARK + "%").param("b", "%" + CELL_MARK + "%").query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM outbox_events WHERE outbox_events::text LIKE :a OR outbox_events::text LIKE :b")
                .param("a", "%" + NAME_MARK + "%").param("b", "%" + CELL_MARK + "%").query(Integer.class).single()).isZero();
        assertThat(output.getAll()).doesNotContain(NAME_MARK, CELL_MARK);
    }

    @Test
    void rejectionsDoNotEchoTheNameNorTheContentEitherInTheResponseOrTheLogs(CapturedOutput output) throws Exception {
        List<MvcResult> rejected = List.of(
                // 415: ni extensión ni Content-Type de CSV.
                upload(NAME_MARK + ".exe", "application/octet-stream", utf8("col\n" + CELL_MARK + "\n")),
                // 400: UTF-8 inválido tras la marca.
                upload(NAME_MARK + ".csv", "text/csv", concat(utf8(CELL_MARK), new byte[] {(byte) 0xC3, (byte) 0x28})),
                // 400: cabecera en blanco con contenido detrás.
                upload(NAME_MARK + ".csv", "text/csv", utf8("   \n" + CELL_MARK + "\n")),
                // 400: cabecera desmesurada que empieza por la marca.
                upload(NAME_MARK + ".csv", "text/csv", utf8(CELL_MARK + "x".repeat(70_000))));

        assertThat(rejected).extracting(r -> r.getResponse().getStatus()).containsExactly(415, 400, 400, 400);
        for (MvcResult result : rejected) {
            assertThat(result.getResponse().getContentAsString()).doesNotContain(NAME_MARK, CELL_MARK);
        }
        assertThat(output.getAll()).doesNotContain(NAME_MARK, CELL_MARK);
        assertThat(storedInputs()).isEmpty();
    }

    @Test
    void aMissingPartErrorOnlyNamesThePartTheServerDeclares() throws Exception {
        MvcResult result = mvc.perform(multipart("/api/v1/jobs/csv")
                .file(new MockMultipartFile(NAME_MARK, "x.csv", "text/csv", utf8("a\n"))))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString()).doesNotContain(NAME_MARK);
    }

    // --- cabeceras y endpoints ---

    @Test
    void everyResponseCarriesNosniff() throws Exception {
        mvc.perform(get("/api/v1/jobs")).andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        mvc.perform(get("/api/v1/nope")).andExpect(status().isNotFound())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        mvc.perform(get("/actuator/health")).andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @ParameterizedTest(name = "[{index}] /actuator/{0} no está expuesto")
    @ValueSource(strings = {"env", "beans", "heapdump", "threaddump", "configprops", "mappings", "loggers", "shutdown",
            "conditions", "caches", "scheduledtasks", "flyway", "sbom"})
    void sensitiveActuatorEndpointsAreNotExposed(String endpoint) throws Exception {
        mvc.perform(get("/actuator/" + endpoint)).andExpect(status().isNotFound());
    }

    @Test
    void healthDoesNotRevealComponentsNorDetails() throws Exception {
        String body = mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain("components", "details", "jdbc:", "postgres", "rabbit", "diskSpace");
    }

    @ParameterizedTest(name = "[{index}] la ruta {0} no sale del recurso")
    @ValueSource(strings = {"..", "%2e%2e", "../../etc/passwd", "..%2F..%2Fetc%2Fpasswd", "1; DROP TABLE jobs", "' OR '1'='1",
            "00000000-0000-0000-0000-000000000000/../x"})
    void jobIdsAreOnlyEverUuids(String id) throws Exception {
        int httpStatus = mvc.perform(get("/api/v1/jobs/{id}/result", id)).andReturn().getResponse().getStatus();

        assertThat(httpStatus).isIn(400, 404);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
