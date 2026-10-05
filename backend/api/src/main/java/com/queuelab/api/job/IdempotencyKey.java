package com.queuelab.api.job;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** Validación de la cabecera {@code Idempotency-Key} y huella de la carga con la que se compara. */
final class IdempotencyKey {

    static final String HEADER = "Idempotency-Key";
    static final int MAX_LENGTH = 255;
    /** Solo ASCII visible (sin espacios ni control): se guarda y se compara tal cual. */
    private static final Pattern FORMAT = Pattern.compile("[\\x21-\\x7E]+");

    private IdempotencyKey() {
    }

    /** Devuelve la clave o lanza {@link InvalidRequestException}. */
    static String validate(String key) {
        if (key == null || key.isEmpty()) {
            throw new InvalidRequestException("La cabecera '" + HEADER + "' no puede estar vacía");
        }
        if (key.length() > MAX_LENGTH) {
            throw new InvalidRequestException("La cabecera '" + HEADER + "' no puede superar " + MAX_LENGTH + " caracteres");
        }
        if (!FORMAT.matcher(key).matches()) {
            throw new InvalidRequestException(
                    "La cabecera '" + HEADER + "' solo admite caracteres ASCII visibles, sin espacios");
        }
        return key;
    }

    /**
     * SHA-256 (hex) de los campos de la carga. Cada campo lleva su longitud delante para que
     * {@code ("ab","c")} y {@code ("a","bc")} no produzcan la misma huella.
     */
    static String fingerprint(String... fields) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String field : fields) {
                byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                digest.update((bytes.length + ":").getBytes(StandardCharsets.US_ASCII));
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
