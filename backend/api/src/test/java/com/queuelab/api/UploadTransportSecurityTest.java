package com.queuelab.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.queuelab.api.support.PostgresTestConfiguration;

/**
 * Lo que MockMvc no puede probar de la subida porque el contenedor (Tomcat) lo resuelve antes que Spring:
 * multipart mal formado, demasiadas partes, cuerpo mayor que el límite de la petición y rutas con barras
 * codificadas. En todos los casos la respuesta es un error del cliente, sin trazas ni rutas, y no queda nada guardado.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "queuelab.csv.max-file-size=1KB",
        "spring.servlet.multipart.max-request-size=4KB"})
@Import(PostgresTestConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class UploadTransportSecurityTest {

    static final String BOUNDARY = "queuelabBoundary7c1d";

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Value("${queuelab.storage.directory}")
    Path storageDirectory;

    final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void clean() throws Exception {
        jdbc.sql("DELETE FROM jobs").update();
        try (Stream<Path> files = Files.list(Files.createDirectories(storageDirectory.toAbsolutePath().resolve("inputs")))) {
            files.forEach(f -> f.toFile().delete());
        }
    }

    private HttpResponse<String> post(String contentType, byte[] body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/jobs/csv"))
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static byte[] part(String disposition, String contentType, String content) {
        return ("--" + BOUNDARY + "\r\nContent-Disposition: " + disposition + "\r\nContent-Type: " + contentType
                + "\r\n\r\n" + content + "\r\n").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] closing() {
        return ("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] join(byte[]... chunks) {
        return Stream.of(chunks).reduce(new byte[0], (a, b) -> {
            byte[] out = new byte[a.length + b.length];
            System.arraycopy(a, 0, out, 0, a.length);
            System.arraycopy(b, 0, out, a.length, b.length);
            return out;
        });
    }

    private void assertCleanClientError(HttpResponse<String> response, CapturedOutput output) throws Exception {
        assertThat(response.statusCode()).isBetween(400, 499);
        assertThat(response.body()).doesNotContain("Exception", "\tat ", "org.apache", "org.springframework",
                storageDirectory.toAbsolutePath().toString(), "/Users/");
        assertThat(output.getAll()).doesNotContain("Error no controlado");
        assertThat(jdbc.sql("SELECT count(*) FROM jobs").query(Integer.class).single()).isZero();
        try (Stream<Path> files = Files.list(storageDirectory.toAbsolutePath().resolve("inputs"))) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void multipartWithoutBoundaryIsAClientError(CapturedOutput output) throws Exception {
        assertCleanClientError(post("multipart/form-data", "garbage".getBytes(StandardCharsets.UTF_8)), output);
    }

    @Test
    void aTruncatedMultipartBodyIsAClientError(CapturedOutput output) throws Exception {
        byte[] body = join(part("form-data; name=\"file\"; filename=\"a.csv\"", "text/csv", "a,b\n1,2"));
        // Sin la línea de cierre del multipart.
        assertCleanClientError(post("multipart/form-data; boundary=" + BOUNDARY, body), output);
    }

    @Test
    void aBodyThatIsNotWhatTheBoundaryAnnouncesIsAClientError(CapturedOutput output) throws Exception {
        assertCleanClientError(post("multipart/form-data; boundary=" + BOUNDARY,
                "esto no es multipart\r\nni de lejos\r\n".getBytes(StandardCharsets.UTF_8)), output);
    }

    @Test
    void aRequestOverTheRequestLimitIsRejectedWith413(CapturedOutput output) throws Exception {
        byte[] big = join(part("form-data; name=\"file\"; filename=\"a.csv\"", "text/csv", "a\n" + "1\n".repeat(50_000)),
                closing());

        HttpResponse<String> response = post("multipart/form-data; boundary=" + BOUNDARY, big);

        assertThat(response.statusCode()).isEqualTo(413);
        assertCleanClientError(response, output);
    }

    @Test
    void manyPartsAreRejectedWithAClientError(CapturedOutput output) throws Exception {
        byte[] body = new byte[0];
        for (int i = 0; i < 100; i++) {
            body = join(body, part("form-data; name=\"extra" + i + "\"", "text/plain", "x"));
        }
        body = join(body, closing());

        assertCleanClientError(post("multipart/form-data; boundary=" + BOUNDARY, body), output);
    }

    @ParameterizedTest(name = "[{index}] {0} no llega a ningún recurso")
    @ValueSource(strings = {"..%2F..%2Fetc%2Fpasswd", "%2e%2e/%2e%2e/etc/passwd", "..%5C..%5Cwindows", "%00", "..;/x"})
    void encodedTraversalInThePathIsAClientError(String segment) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/jobs/" + segment + "/result"))
                .GET().build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isBetween(400, 404);
        assertThat(response.body()).doesNotContain("root:", "Exception", "/Users/");
    }
}
