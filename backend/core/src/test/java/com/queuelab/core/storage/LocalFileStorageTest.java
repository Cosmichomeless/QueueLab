package com.queuelab.core.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LocalFileStorageTest {

    @TempDir
    Path root;

    Path base;
    LocalFileStorage storage;

    @BeforeEach
    void setUp() {
        base = root.resolve("storage");
        storage = new LocalFileStorage(base);
    }

    private static InputStream bytes(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(InputStream in) throws IOException {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void storesAndReadsBackWithAnOpaqueReference() throws IOException {
        StoredFile stored = storage.store(StorageArea.INPUT, "job-1.csv", bytes("a,b\n1,2\n"));

        assertThat(stored.reference()).isEqualTo("inputs/job-1.csv");
        assertThat(stored.size()).isEqualTo(8);
        assertThat(storage.exists(stored.reference())).isTrue();
        assertThat(storage.size(stored.reference())).isEqualTo(8);
        assertThat(read(storage.open(stored.reference()))).isEqualTo("a,b\n1,2\n");
        assertThat(base.resolve("inputs/job-1.csv")).exists();
    }

    @Test
    void inputsAndResultsLiveInSeparateAreas() {
        StoredFile in = storage.store(StorageArea.INPUT, "x.json", bytes("1"));
        StoredFile out = storage.store(StorageArea.RESULT, "x.json", bytes("2"));

        assertThat(in.reference()).isNotEqualTo(out.reference());
        assertThat(base.resolve("results/x.json")).exists();
    }

    @Test
    void storingTheSameNameReplacesTheFileSoRetriesCanRewriteTheirResult() throws IOException {
        storage.store(StorageArea.RESULT, "r.json", bytes("old"));

        storage.store(StorageArea.RESULT, "r.json", bytes("new"));

        assertThat(read(storage.open("results/r.json"))).isEqualTo("new");
    }

    @Test
    void deleteRemovesTheFileAndReportsWhetherItExisted() {
        StoredFile stored = storage.store(StorageArea.INPUT, "d.csv", bytes("x"));

        assertThat(storage.delete(stored.reference())).isTrue();
        assertThat(storage.delete(stored.reference())).isFalse();
        assertThat(storage.exists(stored.reference())).isFalse();
    }

    @Test
    void missingFileIsReportedAsNotFound() {
        assertThatThrownBy(() -> storage.open("inputs/nope.csv")).isInstanceOf(StoredFileNotFoundException.class);
        assertThatThrownBy(() -> storage.size("inputs/nope.csv")).isInstanceOf(StoredFileNotFoundException.class);
        assertThat(storage.exists("inputs/nope.csv")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"../evil.csv", "..", "a/b.csv", "a\\b.csv", "/etc/passwd", ".hidden", "..hidden",
            "a..b", "", " ", "nombre con espacios", "nul\0.csv", "ñ.csv"})
    void namesThatCouldEscapeOrAreMalformedAreRejectedOnStore(String name) {
        assertThatThrownBy(() -> storage.store(StorageArea.INPUT, name, bytes("x")))
                .isInstanceOf(InvalidStorageReferenceException.class);
    }

    @Test
    void nullAndTooLongNamesAreRejected() {
        assertThatThrownBy(() -> storage.store(StorageArea.INPUT, null, bytes("x")))
                .isInstanceOf(InvalidStorageReferenceException.class);
        assertThatThrownBy(() -> storage.store(StorageArea.INPUT, "a".repeat(129), bytes("x")))
                .isInstanceOf(InvalidStorageReferenceException.class);
        assertThat(storage.store(StorageArea.INPUT, "a".repeat(128), bytes("x")).size()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"../secret.txt", "inputs/../../secret.txt", "inputs/../results/x", "/etc/passwd",
            "inputs//x.csv", "inputs/a/b.csv", "other/x.csv", "x.csv", "inputs", "inputs/", "", "results/..",
            "inputs/..%2f", "inputs\\x.csv"})
    void referencesThatCouldEscapeAreRejectedOnEveryOperation(String reference) {
        assertThatThrownBy(() -> storage.open(reference)).isInstanceOf(InvalidStorageReferenceException.class);
        assertThatThrownBy(() -> storage.size(reference)).isInstanceOf(InvalidStorageReferenceException.class);
        assertThatThrownBy(() -> storage.exists(reference)).isInstanceOf(InvalidStorageReferenceException.class);
        assertThatThrownBy(() -> storage.delete(reference)).isInstanceOf(InvalidStorageReferenceException.class);
    }

    @Test
    void nullReferenceIsRejected() {
        assertThatThrownBy(() -> storage.open(null)).isInstanceOf(InvalidStorageReferenceException.class);
    }

    @Test
    void rejectedTraversalNeverTouchesFilesOutsideTheBase() throws IOException {
        Path outside = root.resolve("secret.txt");
        Files.writeString(outside, "secreto");

        assertThatThrownBy(() -> storage.delete("inputs/../../secret.txt"))
                .isInstanceOf(InvalidStorageReferenceException.class);
        assertThatThrownBy(() -> storage.store(StorageArea.INPUT, "../secret.txt", bytes("pisado")))
                .isInstanceOf(InvalidStorageReferenceException.class);

        assertThat(Files.readString(outside)).isEqualTo("secreto");
    }

    @Test
    void aSymlinkInsideTheAreaCannotPointOutsideTheBase() throws IOException {
        Path outside = root.resolve("secret.txt");
        Files.writeString(outside, "secreto");
        Files.createSymbolicLink(base.resolve("inputs/link.csv"), outside);

        assertThatThrownBy(() -> storage.open("inputs/link.csv")).isInstanceOf(InvalidStorageReferenceException.class);
        assertThatThrownBy(() -> storage.delete("inputs/link.csv")).isInstanceOf(InvalidStorageReferenceException.class);
        assertThatThrownBy(() -> storage.store(StorageArea.INPUT, "link.csv", bytes("pisado")))
                .isInstanceOf(InvalidStorageReferenceException.class);

        assertThat(Files.readString(outside)).isEqualTo("secreto");
    }

    @Test
    void aSymlinkedAreaDirectoryCannotPointOutsideTheBase() throws IOException {
        Path elsewhere = Files.createDirectory(root.resolve("elsewhere"));
        Path results = base.resolve("results");
        Files.delete(results);
        Files.createSymbolicLink(results, elsewhere);

        assertThatThrownBy(() -> storage.store(StorageArea.RESULT, "r.json", bytes("x")))
                .isInstanceOf(InvalidStorageReferenceException.class);

        try (Stream<Path> files = Files.list(elsewhere)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void aFailedWriteLeavesNoFileBehindNeitherTheTargetNorATemporary() throws IOException {
        InputStream failing = new InputStream() {
            int sent;

            @Override
            public int read() throws IOException {
                if (sent++ < 10) {
                    return 'x';
                }
                throw new IOException("conexión cortada");
            }
        };

        assertThatThrownBy(() -> storage.store(StorageArea.INPUT, "partial.csv", failing))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("inputs/partial.csv");

        try (Stream<Path> files = Files.list(base.resolve("inputs"))) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void aFailedReplacementKeepsThePreviousContent() throws IOException {
        storage.store(StorageArea.RESULT, "r.json", bytes("previo"));
        InputStream failing = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("fallo");
            }
        };

        assertThatThrownBy(() -> storage.store(StorageArea.RESULT, "r.json", failing))
                .isInstanceOf(StorageException.class);

        assertThat(read(storage.open("results/r.json"))).isEqualTo("previo");
    }

    @Test
    void createsTheDirectoryTreeWhenMissingAndAcceptsRelativeBases() {
        Path nested = root.resolve("a/b/c");

        new LocalFileStorage(nested);

        assertThat(nested.resolve("inputs")).isDirectory();
        assertThat(nested.resolve("results")).isDirectory();
    }
}
