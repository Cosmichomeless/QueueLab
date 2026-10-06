package com.queuelab.api.job;

import java.util.UUID;

/** El trabajo existe y terminó, pero no tiene fichero de resultado (o ya no está en el almacenamiento). */
public class ResultNotFoundException extends RuntimeException {

    public ResultNotFoundException(UUID id) {
        super("El trabajo " + id + " no tiene fichero de resultado");
    }
}
