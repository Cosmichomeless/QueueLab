package com.queuelab.api.job;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.queuelab.core.job.Job;
import com.queuelab.core.job.JobStatus;
import com.queuelab.core.logging.LogContext;

@RestController
@RequestMapping("/api/v1/jobs")
public class JobController {

    private static final Logger log = LoggerFactory.getLogger(JobController.class);

    private final JobService service;
    private final CsvUploadService csvUploads;

    public JobController(JobService service, CsvUploadService csvUploads) {
        this.service = service;
        this.csvUploads = csvUploads;
    }

    /**
     * Crea el trabajo en {@code QUEUED} y responde de inmediato, sin esperar a que se procese.
     *
     * <p>Con la cabecera opcional {@code Idempotency-Key}, repetir la misma petición responde
     * {@code 200} con el trabajo ya existente (y {@code Idempotent-Replayed: true}); la misma clave con otra
     * carga responde {@code 409}.
     */
    @PostMapping
    ResponseEntity<JobResponse> submit(
            @RequestBody SubmitJobRequest request,
            @RequestHeader(name = IdempotencyKey.HEADER, required = false) String idempotencyKey) {
        Submission submission = service.submit(request == null ? null : request.type(), idempotencyKey);
        Job job = submission.job();
        var location = ServletUriComponentsBuilder.fromCurrentRequest().replaceQuery(null)
                .path("/{id}").build(job.id());
        if (submission.created()) {
            logAccepted("aceptado", job);
            return ResponseEntity.created(location).body(JobResponse.from(job));
        }
        return ResponseEntity.ok().location(location).header("Idempotent-Replayed", "true")
                .body(service.view(job));
    }

    /**
     * Sube un CSV ({@code multipart/form-data}, parte {@code file}) y crea el trabajo {@code csv-import} en
     * {@code QUEUED}: responde {@code 201} sin esperar al procesamiento. Rechaza con {@code 413} lo que supere
     * el tamaño máximo, {@code 415} lo que no se declare como CSV y {@code 400} un fichero vacío o que no sea
     * UTF-8; en ningún caso queda un fichero guardado. Ver {@code docs/csv-workload.md}.
     */
    @PostMapping(path = "/csv", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<JobResponse> uploadCsv(@RequestPart(name = "file", required = false) MultipartFile file) {
        Job job = csvUploads.upload(file);
        logAccepted("aceptado", job);
        var location = ServletUriComponentsBuilder.fromCurrentRequest().replacePath("/api/v1/jobs/{id}").build(job.id());
        return ResponseEntity.created(location).body(JobResponse.from(job));
    }

    /**
     * Lista trabajos, los más recientes primero. Para la página siguiente se envía el
     * {@code nextCursor} de la respuesta anterior.
     */
    @GetMapping
    JobPage list(
            @RequestParam(required = false) JobStatus status,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "" + JobService.DEFAULT_PAGE_SIZE) int limit) {
        return service.list(status, cursor, limit);
    }

    /**
     * Reintenta a mano un trabajo {@code FAILED}: responde {@code 202} con el trabajo ya en {@code QUEUED}
     * (se republicará por el outbox). Un trabajo en cualquier otro estado responde {@code 409}.
     */
    @PostMapping("/{id}/retry")
    ResponseEntity<JobResponse> retry(@PathVariable UUID id) {
        Job job = service.retry(id);
        logAccepted("reencolado por reintento manual", job);
        var location = ServletUriComponentsBuilder.fromCurrentRequest().replacePath("/api/v1/jobs/{id}").build(id);
        return ResponseEntity.accepted().location(location).body(JobResponse.from(job));
    }

    /** Una línea por trabajo que entra en la cola, con su id y el de correlación en el MDC. Nunca el contenido. */
    private static void logAccepted(String what, Job job) {
        try (LogContext.Scope ignored = LogContext.with(null, job.id())) {
            log.info("Trabajo {} {} (tipo {})", job.id(), what, job.type());
        }
    }

    /** Historial de reintentos manuales del trabajo, del más antiguo al más reciente. */
    @GetMapping("/{id}/retries")
    List<JobRetryResponse> retries(@PathVariable UUID id) {
        return service.retries(id).stream().map(JobRetryResponse::from).toList();
    }

    @GetMapping("/{id}")
    JobResponse get(@PathVariable UUID id) {
        return service.view(service.get(id));
    }

    /**
     * Descarga el fichero de resultado de un trabajo {@code COMPLETED} (para {@code csv-import}, las
     * estadísticas en JSON). {@code 409} si el trabajo aún no terminó o falló; {@code 404} si no existe o no
     * produce fichero.
     */
    @GetMapping("/{id}/result")
    ResponseEntity<InputStreamResource> downloadResult(@PathVariable UUID id) {
        ResultDownload download = service.openResult(id);
        ResultFile file = download.file();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .contentLength(file.size())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.name()).build().toString())
                .body(new InputStreamResource(download.content()));
    }
}
