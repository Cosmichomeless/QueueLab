package com.queuelab.core.messaging;

import java.util.UUID;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;

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
        return MessageBuilder.withBody(body.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setContentEncoding("UTF-8")
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .build();
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
