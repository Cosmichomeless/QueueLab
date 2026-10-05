package com.queuelab.api.job;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.queuelab.core.job.Job;
import com.queuelab.core.job.JobStatus;

@RestController
@RequestMapping("/api/v1/jobs")
public class JobController {

    private final JobService service;

    public JobController(JobService service) {
        this.service = service;
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
            return ResponseEntity.created(location).body(JobResponse.from(job));
        }
        return ResponseEntity.ok().location(location).header("Idempotent-Replayed", "true")
                .body(JobResponse.from(job));
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
        var location = ServletUriComponentsBuilder.fromCurrentRequest().replacePath("/api/v1/jobs/{id}").build(id);
        return ResponseEntity.accepted().location(location).body(JobResponse.from(job));
    }

    /** Historial de reintentos manuales del trabajo, del más antiguo al más reciente. */
    @GetMapping("/{id}/retries")
    List<JobRetryResponse> retries(@PathVariable UUID id) {
        return service.retries(id).stream().map(JobRetryResponse::from).toList();
    }

    @GetMapping("/{id}")
    JobResponse get(@PathVariable UUID id) {
        return JobResponse.from(service.get(id));
    }
}
