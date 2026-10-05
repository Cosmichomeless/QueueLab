package com.queuelab.api.job;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Límites de entrada del campo {@code type}: obligatorio, formato {@code kebab-case}, como máximo
 * {@value #MAX_LENGTH} caracteres (el tamaño de la columna) y uno de los tipos conocidos.
 */
@Component
class JobTypeValidator {

    static final int MAX_LENGTH = 100;
    private static final Pattern FORMAT = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

    private final Set<String> knownTypes;

    JobTypeValidator(@Value("${queuelab.jobs.types}") List<String> knownTypes) {
        this.knownTypes = new TreeSet<>(knownTypes);
    }

    /** Devuelve el tipo validado o lanza {@link InvalidRequestException} con un mensaje seguro para el cliente. */
    String validate(String type) {
        if (type == null || type.isBlank()) {
            throw new InvalidRequestException("El campo 'type' es obligatorio");
        }
        if (type.length() > MAX_LENGTH) {
            throw new InvalidRequestException("El campo 'type' no puede superar " + MAX_LENGTH + " caracteres");
        }
        if (!FORMAT.matcher(type).matches()) {
            throw new InvalidRequestException(
                    "El campo 'type' solo admite minúsculas, dígitos y guiones (por ejemplo 'csv-import')");
        }
        if (!knownTypes.contains(type)) {
            throw new InvalidRequestException(
                    "Tipo de trabajo desconocido. Tipos admitidos: " + String.join(", ", knownTypes));
        }
        return type;
    }
}
