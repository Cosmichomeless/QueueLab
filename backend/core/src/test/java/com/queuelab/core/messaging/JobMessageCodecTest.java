package com.queuelab.core.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;

class JobMessageCodecTest {

    private static Message body(String json) {
        return new Message(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void roundTripKeepsTheJobId() {
        UUID id = UUID.randomUUID();

        Message wire = JobMessageCodec.encode(JobMessage.forJob(id));

        assertThat(JobMessageCodec.decode(wire)).isEqualTo(new JobMessage(1, id));
    }

    @Test
    void wireFormatIsVersionedJsonAndPersistent() {
        UUID id = UUID.fromString("2f6c0d52-6f0e-4a29-9d3a-1f2b7a7b3f10");

        Message wire = JobMessageCodec.encode(JobMessage.forJob(id));

        assertThat(new String(wire.getBody(), StandardCharsets.UTF_8))
                .isEqualTo("{\"version\":1,\"jobId\":\"2f6c0d52-6f0e-4a29-9d3a-1f2b7a7b3f10\"}");
        assertThat(wire.getMessageProperties().getContentType()).isEqualTo(MessageProperties.CONTENT_TYPE_JSON);
        assertThat(wire.getMessageProperties().getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
    }

    @Test
    void unknownExtraFieldsAreIgnoredSoTheContractCanGrowCompatibly() {
        UUID id = UUID.randomUUID();

        JobMessage decoded = JobMessageCodec.decode(
                body("{\"version\":1,\"jobId\":\"" + id + "\",\"hint\":\"later\"}"));

        assertThat(decoded.jobId()).isEqualTo(id);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "not json", "[]", "null", "42", "{}",
            "{\"jobId\":\"2f6c0d52-6f0e-4a29-9d3a-1f2b7a7b3f10\"}",
            "{\"version\":\"1\",\"jobId\":\"2f6c0d52-6f0e-4a29-9d3a-1f2b7a7b3f10\"}",
            "{\"version\":2,\"jobId\":\"2f6c0d52-6f0e-4a29-9d3a-1f2b7a7b3f10\"}",
            "{\"version\":1}",
            "{\"version\":1,\"jobId\":null}",
            "{\"version\":1,\"jobId\":7}",
            "{\"version\":1,\"jobId\":\"not-a-uuid\"}"
    })
    void malformedBodiesAreRejected(String json) {
        assertThatThrownBy(() -> JobMessageCodec.decode(body(json)))
                .isInstanceOf(MalformedJobMessageException.class);
    }

    @Test
    void messageWithoutJobIdCannotBeBuilt() {
        assertThatThrownBy(() -> JobMessage.forJob(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void correlationIdTravelsAsAnAmqpHeaderWithoutChangingTheBody() {
        UUID id = UUID.randomUUID();
        String json = JobMessageCodec.toJson(JobMessage.forJob(id));

        Message wire = JobMessageCodec.encodeJson(json, "corr-123");

        assertThat(JobMessageCodec.correlationIdOf(wire)).isEqualTo("corr-123");
        assertThat(wire.getBody()).isEqualTo(JobMessageCodec.encodeJson(json).getBody());
        assertThat(JobMessageCodec.decode(wire)).isEqualTo(new JobMessage(1, id));
    }

    @Test
    void aMessageWithoutOrWithAnInvalidCorrelationHeaderYieldsNull() {
        Message plain = JobMessageCodec.encode(JobMessage.forJob(UUID.randomUUID()));
        assertThat(JobMessageCodec.correlationIdOf(plain)).isNull();

        Message hostile = JobMessageCodec.encodeJson("{}", "a b\nc");
        assertThat(hostile.getMessageProperties().getHeaders()).containsKey("x-correlation-id");
        assertThat(JobMessageCodec.correlationIdOf(hostile)).isNull();
    }

    @Test
    void traceContextAndDeliveryTimeTravelAsHeadersWithoutChangingTheBody() {
        String json = JobMessageCodec.toJson(JobMessage.forJob(UUID.randomUUID()));
        String traceparent = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
        Instant enqueuedAt = Instant.parse("2026-03-01T08:00:00.123Z");

        Message wire = JobMessageCodec.encodeJson(json, "corr-123", traceparent, enqueuedAt);

        assertThat(JobMessageCodec.traceContextOf(wire).getSpanId()).isEqualTo("b7ad6b7169203331");
        assertThat(JobMessageCodec.enqueuedAtOf(wire)).isEqualTo(enqueuedAt);
        assertThat(JobMessageCodec.correlationIdOf(wire)).isEqualTo("corr-123");
        assertThat(wire.getBody()).isEqualTo(JobMessageCodec.encodeJson(json).getBody());
    }

    @Test
    void aMessageWithoutOrWithInvalidTraceHeadersYieldsNull() {
        Message plain = JobMessageCodec.encode(JobMessage.forJob(UUID.randomUUID()));
        assertThat(JobMessageCodec.traceContextOf(plain)).isNull();
        assertThat(JobMessageCodec.enqueuedAtOf(plain)).isNull();

        Message hostile = JobMessageCodec.encodeJson("{}", null, "basura", Instant.EPOCH);
        assertThat(hostile.getMessageProperties().getHeaders()).containsKey("traceparent");
        assertThat(JobMessageCodec.traceContextOf(hostile)).isNull();
        assertThat(JobMessageCodec.enqueuedAtOf(hostile)).isNull();

        Message notANumber = JobMessageCodec.encodeJson("{}");
        notANumber.getMessageProperties().setHeader("x-queuelab-enqueued-at", "ayer");
        assertThat(JobMessageCodec.enqueuedAtOf(notANumber)).isNull();
    }
}
