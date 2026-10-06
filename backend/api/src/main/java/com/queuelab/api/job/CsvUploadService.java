package com.queuelab.api.job;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

import com.queuelab.api.queue.QueueBackpressure;
import com.queuelab.core.job.Job;
import com.queuelab.core.job.JobRepository;
import com.queuelab.core.outbox.OutboxEvent;
import com.queuelab.core.outbox.OutboxRepository;
import com.queuelab.core.storage.FileStorage;
import com.queuelab.core.storage.StorageArea;
import com.queuelab.core.storage.StoredFile;

/**
 * Subida de un CSV: valida, guarda el fichero en el almacenamiento y crea el trabajo {@code csv-import} en
 * {@code QUEUED} con su evento de outbox (la base de datos solo guarda la referencia al fichero).
 *
 * <p>No deja ficheros huérfanos: todo lo que se puede comprobar se comprueba <em>antes</em> de escribir, y si
 * la transacción falla después de guardar el fichero se borra. Solo una caída del proceso justo entre ambos
 * pasos puede dejar un fichero sin trabajo; lo recoge la limpieza de #32.
 *
 * <p>El nombre del fichero que manda el cliente solo se usa para reconocer la extensión; en el almacenamiento
 * se guarda como {@code <jobId>.csv}.
 */
@Service
public class CsvUploadService {

    static final String TYPE = "csv-import";
    /** Cuánto de la primera línea (cabecera) se lee para la comprobación mínima. */
    static final int HEADER_PROBE_BYTES = 64 * 1024;

    private static final Logger log = LoggerFactory.getLogger(CsvUploadService.class);

    private final FileStorage storage;
    private final JobRepository jobs;
    private final OutboxRepository outbox;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final long maxBytes;
    private final QueueBackpressure backpressure;

    public CsvUploadService(FileStorage storage, JobRepository jobs, OutboxRepository outbox,
            TransactionTemplate transaction, Clock clock,
            @Value("${queuelab.csv.max-file-size:10MB}") DataSize maxFileSize, QueueBackpressure backpressure) {
        this.backpressure = backpressure;
        this.storage = storage;
        this.jobs = jobs;
        this.outbox = outbox;
        this.transaction = transaction;
        this.clock = clock;
        this.maxBytes = maxFileSize.toBytes();
    }

    public Job upload(MultipartFile file) {
        validate(file);
        // Antes de guardar el fichero: una cola saturada no debe gastar disco en lo que va a rechazar.
        backpressure.ensureCapacity();

        UUID id = UUID.randomUUID();
        StoredFile stored;
        try (InputStream content = file.getInputStream()) {
            stored = storage.store(StorageArea.INPUT, id + ".csv", content);
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo leer el fichero subido", e);
        }
        try {
            return transaction.execute(status -> {
                Job job = Job.queued(id, TYPE, clock.instant().truncatedTo(ChronoUnit.MICROS));
                jobs.insert(job);
                jobs.attachInput(id, stored.reference());
                outbox.insert(OutboxEvent.jobQueued(job, job.createdAt()));
                return job;
            });
        } catch (RuntimeException e) {
            discard(stored);
            throw e;
        }
    }

    private void validate(MultipartFile file) {
        if (file == null) {
            throw new InvalidRequestException("Falta la parte 'file' con el CSV");
        }
        if (file.getSize() > maxBytes) {
            throw new UploadTooLargeException(maxBytes);
        }
        if (!declaredAsCsv(file)) {
            throw new UnsupportedUploadTypeException();
        }
        if (file.isEmpty()) {
            throw new InvalidRequestException("El fichero está vacío: debe tener al menos la cabecera");
        }
        checkHeaderLine(file);
    }

    private static boolean declaredAsCsv(MultipartFile file) {
        String contentType = file.getContentType();
        if (contentType != null) {
            try {
                if (MediaType.parseMediaType(contentType).isCompatibleWith(MediaType.valueOf("text/csv"))) {
                    return true;
                }
            } catch (InvalidMediaTypeException ignored) {
                // Un Content-Type ilegible no impide que la extensión lo identifique.
            }
        }
        String name = file.getOriginalFilename();
        return name != null && name.toLowerCase(Locale.ROOT).endsWith(".csv");
    }

    /**
     * Comprobación mínima sin parsear el fichero: la primera línea es UTF-8 válido y no está en blanco. La
     * validación completa de la cabecera (nombres, duplicados, columnas) la hace el worker en streaming, que
     * es quien conoce las reglas de {@code docs/csv-workload.md}.
     */
    private void checkHeaderLine(MultipartFile file) {
        byte[] probe;
        try (InputStream in = file.getInputStream()) {
            probe = in.readNBytes(HEADER_PROBE_BYTES);
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo leer el fichero subido", e);
        }
        int end = indexOf(probe, (byte) '\n');
        boolean complete = end >= 0 || probe.length < HEADER_PROBE_BYTES;
        if (!complete) {
            throw new InvalidRequestException("La primera línea (cabecera) es demasiado larga");
        }
        int from = hasBom(probe) ? 3 : 0;
        int to = end >= 0 ? end : probe.length;
        String header;
        try {
            header = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(probe, from, Math.max(0, to - from))).toString();
        } catch (CharacterCodingException e) {
            throw new InvalidRequestException("El fichero no es UTF-8 válido");
        }
        if (header.isBlank()) {
            throw new InvalidRequestException("La primera línea (cabecera) está vacía");
        }
    }

    private void discard(StoredFile stored) {
        try {
            storage.delete(stored.reference());
        } catch (RuntimeException e) {
            log.warn("No se pudo borrar {} tras fallar el alta del trabajo; lo recogerá la limpieza", stored.reference(), e);
        }
    }

    private static boolean hasBom(byte[] bytes) {
        return bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF;
    }

    private static int indexOf(byte[] bytes, byte value) {
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == value) {
                return i;
            }
        }
        return -1;
    }

    static String describe(long bytes) {
        long mib = 1024 * 1024;
        return bytes % mib == 0 ? bytes / mib + " MiB" : bytes + " bytes";
    }
}
