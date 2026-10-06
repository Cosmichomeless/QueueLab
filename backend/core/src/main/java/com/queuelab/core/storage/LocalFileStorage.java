package com.queuelab.core.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * Almacenamiento en un directorio local. Todo acceso pasa por {@link #resolve}, que garantiza que la ruta
 * final queda dentro de {@code <base>/<zona>}: el formato de nombre ya excluye separadores y {@code ..}, y
 * además se comprueba la ruta real para que un enlace simbólico no pueda sacar el acceso del directorio.
 */
public class LocalFileStorage implements FileStorage {

    private static final String TEMP_PREFIX = ".tmp-";

    private final Path base;
    private final Path realBase;

    public LocalFileStorage(Path base) {
        this.base = base.toAbsolutePath().normalize();
        try {
            for (StorageArea area : StorageArea.values()) {
                Files.createDirectories(this.base.resolve(area.directory()));
            }
            // La ruta real del directorio base es la referencia contra la que se comparan los enlaces.
            this.realBase = this.base.toRealPath();
        } catch (IOException e) {
            throw new StorageException("No se puede preparar el directorio de almacenamiento " + this.base, e);
        }
    }

    @Override
    public StoredFile store(StorageArea area, String name, InputStream content) {
        if (!StorageNames.isValid(name)) {
            throw new InvalidStorageReferenceException("Nombre de fichero no válido");
        }
        String reference = StorageNames.reference(area, name);
        Path target = resolve(reference);
        Path temp = target.resolveSibling(TEMP_PREFIX + UUID.randomUUID());
        try {
            long size = Files.copy(content, temp);
            move(temp, target);
            return new StoredFile(reference, size);
        } catch (IOException e) {
            throw new StorageException("No se pudo guardar " + reference, e);
        } finally {
            deleteQuietly(temp);
        }
    }

    @Override
    public InputStream open(String reference) {
        Path path = resolve(reference);
        try {
            return Files.newInputStream(path);
        } catch (NoSuchFileException e) {
            throw new StoredFileNotFoundException(reference);
        } catch (IOException e) {
            throw new StorageException("No se pudo abrir " + reference, e);
        }
    }

    @Override
    public long size(String reference) {
        Path path = resolve(reference);
        try {
            return Files.size(path);
        } catch (NoSuchFileException e) {
            throw new StoredFileNotFoundException(reference);
        } catch (IOException e) {
            throw new StorageException("No se pudo leer el tamaño de " + reference, e);
        }
    }

    @Override
    public boolean exists(String reference) {
        return Files.isRegularFile(resolve(reference));
    }

    @Override
    public boolean delete(String reference) {
        try {
            return Files.deleteIfExists(resolve(reference));
        } catch (IOException e) {
            throw new StorageException("No se pudo borrar " + reference, e);
        }
    }

    /** Ruta del fichero de una referencia válida y contenida en el directorio base. */
    private Path resolve(String reference) {
        if (reference == null) {
            throw new InvalidStorageReferenceException("Referencia vacía");
        }
        int slash = reference.indexOf('/');
        if (slash <= 0) {
            throw new InvalidStorageReferenceException("Referencia no válida");
        }
        StorageArea area = areaOf(reference.substring(0, slash));
        String name = reference.substring(slash + 1);
        if (!StorageNames.isValid(name)) {
            throw new InvalidStorageReferenceException("Referencia no válida");
        }
        Path path = base.resolve(area.directory()).resolve(name).normalize();
        if (!path.startsWith(base.resolve(area.directory()))) {
            throw new InvalidStorageReferenceException("Referencia fuera del directorio de almacenamiento");
        }
        assertInsideRealBase(path, reference);
        return path;
    }

    private static StorageArea areaOf(String directory) {
        for (StorageArea area : StorageArea.values()) {
            if (area.directory().equals(directory)) {
                return area;
            }
        }
        throw new InvalidStorageReferenceException("Referencia no válida");
    }

    /** Un enlace simbólico (en la zona o en el propio fichero) no puede apuntar fuera del directorio base. */
    private void assertInsideRealBase(Path path, String reference) {
        try {
            Path real = Files.exists(path) ? path.toRealPath() : path.getParent().toRealPath();
            if (!real.startsWith(realBase)) {
                throw new InvalidStorageReferenceException("Referencia fuera del directorio de almacenamiento");
            }
        } catch (IOException e) {
            throw new StorageException("No se pudo comprobar " + reference, e);
        }
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Es el temporal de una escritura que ya terminó o falló; la limpieza periódica (#32) lo recogerá.
        }
    }
}
