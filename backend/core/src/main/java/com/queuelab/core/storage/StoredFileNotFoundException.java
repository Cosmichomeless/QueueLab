package com.queuelab.core.storage;

public class StoredFileNotFoundException extends StorageException {

    public StoredFileNotFoundException(String reference) {
        super("No existe el fichero " + reference, null);
    }
}
