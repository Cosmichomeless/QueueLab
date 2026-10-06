package com.queuelab.core.storage;

/** Zona del almacenamiento: separa lo que sube el cliente de lo que produce el worker. */
public enum StorageArea {
    INPUT("inputs"),
    RESULT("results");

    private final String directory;

    StorageArea(String directory) {
        this.directory = directory;
    }

    public String directory() {
        return directory;
    }
}
