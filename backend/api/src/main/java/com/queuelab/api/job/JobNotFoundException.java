package com.queuelab.api.job;

import java.util.UUID;

public class JobNotFoundException extends RuntimeException {

    public JobNotFoundException(UUID id) {
        super("No existe ningún trabajo con id " + id);
    }
}
