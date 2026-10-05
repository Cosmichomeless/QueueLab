package com.queuelab.api.job;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;

import com.queuelab.core.job.JobCursor;

/** Convierte el cursor en un texto opaco para el cliente y de vuelta. */
final class CursorCodec {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private CursorCodec() {
    }

    static String encode(JobCursor cursor) {
        long micros = ChronoUnit.MICROS.between(Instant.EPOCH, cursor.createdAt());
        String raw = micros + ":" + cursor.id();
        return ENCODER.encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** @throws InvalidRequestException si el cursor no es uno emitido por esta API */
    static JobCursor decode(String value) {
        try {
            String raw = new String(DECODER.decode(value), StandardCharsets.UTF_8);
            int separator = raw.indexOf(':');
            long micros = Long.parseLong(raw.substring(0, separator));
            UUID id = UUID.fromString(raw.substring(separator + 1));
            return new JobCursor(Instant.EPOCH.plus(micros, ChronoUnit.MICROS), id);
        } catch (RuntimeException e) {
            throw new InvalidRequestException("El parámetro 'cursor' no es válido");
        }
    }
}
