package com.queuelab.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import com.queuelab.api.support.PostgresTestConfiguration;

/** El dashboard (otro origen) puede llamar a la API; cualquier otro origen, no. */
@SpringBootTest(properties = {"queuelab.cors.allowed-origins=http://localhost:3000", "queuelab.csv.max-file-size=1KB"})
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
class CorsTest {

    @Autowired
    MockMvc mvc;

    @Test
    void theDashboardOriginIsAllowed() throws Exception {
        mvc.perform(get("/api/v1/jobs").header("Origin", "http://localhost:3000"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"));
    }

    @Test
    void preflightForTheCsvUploadIsAnswered() throws Exception {
        mvc.perform(options("/api/v1/jobs/csv")
                        .header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"))
                .andExpect(header().string("Access-Control-Allow-Methods", "GET,POST"));
    }

    @Test
    void anotherOriginIsRejected() throws Exception {
        mvc.perform(get("/api/v1/jobs").header("Origin", "http://evil.example"))
                .andExpect(status().isForbidden());
    }

    @Test
    void downloadHeadersAreExposedToTheBrowser() throws Exception {
        mvc.perform(get("/api/v1/jobs").header("Origin", "http://localhost:3000"))
                .andExpect(header().string("Access-Control-Expose-Headers", "Location, Content-Disposition"));
    }

    @Test
    void errorsRaisedBeforeTheHandlerKeepTheCorsHeaders() throws Exception {
        var big = new org.springframework.mock.web.MockMultipartFile("file", "big.csv", "text/csv", new byte[4096]);
        mvc.perform(multipart("/api/v1/jobs/csv").file(big).header("Origin", "http://localhost:3000"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"));
    }
}
