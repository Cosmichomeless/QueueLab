package com.queuelab.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.queuelab.api.support.PostgresTestConfiguration;
import com.queuelab.api.support.RedisTestConfiguration;

/** Cuota de envíos con un Redis real: 3 envíos por ventana y cliente; el cuarto recibe 429. */
@SpringBootTest(properties = { "queuelab.rate-limit.enabled=true", "queuelab.rate-limit.max-requests=3",
        "queuelab.rate-limit.window=30s" })
@AutoConfigureMockMvc
@Import({ PostgresTestConfiguration.class, RedisTestConfiguration.class })
class RateLimitTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    /** Cada prueba usa un cliente (IP remota) propio: el contador vive en Redis y no se reinicia entre pruebas. */
    String client;

    @BeforeEach
    void newClient() {
        client = "test-" + UUID.randomUUID();
        jdbc.sql("DELETE FROM jobs").update();
    }

    private ResultActions submit(String remoteAddr) throws Exception {
        return mvc.perform(post("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"noop\"}")
                .with(request -> {
                    request.setRemoteAddr(remoteAddr);
                    return request;
                }));
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    @Test
    void theRequestsOverTheQuotaAreRejectedWith429AndRetryInformation() throws Exception {
        submit(client).andExpect(status().isCreated())
                .andExpect(header().string("RateLimit-Limit", "3"))
                .andExpect(header().string("RateLimit-Remaining", "2"));
        submit(client).andExpect(status().isCreated()).andExpect(header().string("RateLimit-Remaining", "1"));
        submit(client).andExpect(status().isCreated()).andExpect(header().string("RateLimit-Remaining", "0"));

        String retryAfter = submit(client)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Content-Type", containsString("application/problem+json")))
                .andExpect(header().string("RateLimit-Limit", "3"))
                .andExpect(header().string("RateLimit-Remaining", "0"))
                .andExpect(jsonPath("$.title").value("Demasiados envíos"))
                .andExpect(jsonPath("$.limit").value(3))
                .andExpect(jsonPath("$.windowSeconds").value(30))
                .andExpect(jsonPath("$.retryAfterSeconds").isNumber())
                .andReturn().getResponse().getHeader("Retry-After");

        assertThat(Long.parseLong(retryAfter)).isBetween(1L, 30L);
        // Lo rechazado no se guarda ni se encola.
        assertThat(count("jobs")).isEqualTo(3);
        assertThat(count("outbox_events")).isEqualTo(3);
    }

    @Test
    void csvUploadsShareTheQuotaWithJsonSubmissions() throws Exception {
        submit(client).andExpect(status().isCreated());
        submit(client).andExpect(status().isCreated());
        mvc.perform(multipart("/api/v1/jobs/csv")
                        .file(new MockMultipartFile("file", "datos.csv", "text/csv", "a,b\n1,2\n".getBytes()))
                        .with(request -> {
                            request.setRemoteAddr(client);
                            return request;
                        }))
                .andExpect(status().isCreated());

        mvc.perform(multipart("/api/v1/jobs/csv")
                        .file(new MockMultipartFile("file", "datos.csv", "text/csv", "a,b\n1,2\n".getBytes()))
                        .with(request -> {
                            request.setRemoteAddr(client);
                            return request;
                        }))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
        submit(client).andExpect(status().isTooManyRequests());
    }

    @Test
    void eachClientHasItsOwnQuota() throws Exception {
        for (int i = 0; i < 3; i++) {
            submit(client).andExpect(status().isCreated());
        }
        submit(client).andExpect(status().isTooManyRequests());

        submit("otro-" + client).andExpect(status().isCreated());
    }

    @Test
    void readsAndOtherEndpointsAreNotLimited() throws Exception {
        for (int i = 0; i < 3; i++) {
            submit(client).andExpect(status().isCreated());
        }
        submit(client).andExpect(status().isTooManyRequests());

        for (int i = 0; i < 6; i++) {
            mvc.perform(get("/api/v1/jobs").with(request -> {
                request.setRemoteAddr(client);
                return request;
            })).andExpect(status().isOk());
            mvc.perform(get("/api/v1/queue").with(request -> {
                request.setRemoteAddr(client);
                return request;
            })).andExpect(status().isOk());
        }
    }
}
