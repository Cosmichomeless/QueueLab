package com.queuelab.core.messaging;

import java.time.Instant;
import java.util.UUID;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageBuilderSupport;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;

import com.queuelab.core.logging.LogContext;
import com.queuelab.core.tracing.JobTracing;

import io.opentelemetry.api.trace.SpanContext;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Convierte {@link JobMessage} a mensaje AMQP y viceversa. Es la única pieza que conoce el formato
 * del cable, así que API y worker no pueden desviarse.
 */
public final class JobMessageCodec {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private JobMessageCodec() {
    }

    /** Cuerpo JSON del mensaje; también es lo que se guarda en el outbox. */
    public static String toJson(JobMessage message) {
        return JSON.createObjectNode()
                .put("version", message.version())
                .put("jobId", message.jobId().toString())
                .toString();
    }

    /** Cuerpo JSON del mensaje de la cola dead-letter; también es lo que se guarda en el outbox. */
    public static String toJson(DeadLetterMessage message) {
        var node = JSON.createObjectNode()
                .put("version", message.version())
                .put("jobId", message.jobId().toString())
                .put("attempts", message.attempts());
        if (message.cause() == null) {
            node.putNull("cause");
        } else {
            node.put("cause", message.cause());
        }
        return node.toString();
    }

    /** Mensaje persistente, {@code application/json}, listo para publicar. */
    public static Message encode(JobMessage message) {
        return encodeJson(toJson(message));
    }

    /** Como {@link #encode(JobMessage)}, a partir de un cuerpo ya serializado (el del outbox). */
    public static Message encodeJson(String body) {
        return encodeJson(body, null);
    }

    /**
     * Como {@link #encodeJson(String)}, con el id de correlación como cabecera
     * ({@link LogContext#AMQP_HEADER}) para que el worker lo ponga en sus logs. Es solo metadato: el contrato
     * del cuerpo no cambia y un mensaje sin cabecera sigue siendo válido.
     */
    public static Message encodeJson(String body, String correlationId) {
        return encodeJson(body, correlationId, null, null);
    }

    /**
     * Como {@link #encodeJson(String, String)}, con el contexto de la traza ({@link JobTracing#AMQP_HEADER}, W3C
     * {@code traceparent}) y el instante en que el mensaje se entregó al broker
     * ({@link JobTracing#ENQUEUED_AT_HEADER}, ms desde epoch): con ellos el worker continúa la misma traza y
     * mide la espera en la cola. Ambos son opcionales y solo metadatos.
     */
    public static Message encodeJson(String body, String correlationId, String traceparent, Instant enqueuedAt) {
        MessageBuilderSupport<Message> builder = MessageBuilder.withBody(body.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setContentEncoding("UTF-8")
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        if (correlationId != null) {
            builder.setHeader(LogContext.AMQP_HEADER, correlationId);
        }
        if (traceparent != null) {
            builder.setHeader(JobTracing.AMQP_HEADER, traceparent);
        }
        if (enqueuedAt != null) {
            builder.setHeader(JobTracing.ENQUEUED_AT_HEADER, enqueuedAt.toEpochMilli());
        }
        return builder.build();
    }

    /** El id de correlación de la cabecera del mensaje, o {@code null} si falta o no es un id válido. */
    public static String correlationIdOf(Message message) {
        Object value = message.getMessageProperties().getHeader(LogContext.AMQP_HEADER);
        return value instanceof String id && LogContext.isValid(id) ? id : null;
    }

    /** El contexto de traza de la cabecera del mensaje, o {@code null} si falta o no es un {@code traceparent} válido. */
    public static SpanContext traceContextOf(Message message) {
        Object value = message.getMessageProperties().getHeader(JobTracing.AMQP_HEADER);
        return value instanceof String traceparent ? JobTracing.parse(traceparent) : null;
    }

    /** El instante en que el despachador entregó el mensaje al broker, o {@code null} si falta o no es válido. */
    public static Instant enqueuedAtOf(Message message) {
        Object value = message.getMessageProperties().getHeader(JobTracing.ENQUEUED_AT_HEADER);
        return value instanceof Number millis && millis.longValue() > 0 ? Instant.ofEpochMilli(millis.longValue()) : null;
    }

    /**
     * Valida y decodifica el cuerpo.
     *
     * @throws MalformedJobMessageException si no es JSON, falta {@code version}/{@code jobId}, el
     *         id no es un UUID o la versión no está soportada
     */
    public static JobMessage decode(Message message) {
        JsonNode root;
        try {
            root = JSON.readTree(message.getBody());
        } catch (JacksonException e) {
            throw new MalformedJobMessageException("El cuerpo no es JSON válido", e);
        }
        if (root == null || !root.isObject()) {
            throw new MalformedJobMessageException("El cuerpo debe ser un objeto JSON");
        }

        JsonNode version = root.get("version");
        if (version == null || !version.isInt()) {
            throw new MalformedJobMessageException("Falta 'version' o no es un entero");
        }
        if (version.intValue() != JobMessage.CURRENT_VERSION) {
            throw new MalformedJobMessageException("Versión de contrato no soportada: " + version.intValue());
        }

        JsonNode jobId = root.get("jobId");
        if (jobId == null || !jobId.isString()) {
            throw new MalformedJobMessageException("Falta 'jobId' o no es una cadena");
        }
        try {
            return new JobMessage(version.intValue(), UUID.fromString(jobId.asString()));
        } catch (IllegalArgumentException e) {
            throw new MalformedJobMessageException("'jobId' no es un UUID válido", e);
        }
    }
}
