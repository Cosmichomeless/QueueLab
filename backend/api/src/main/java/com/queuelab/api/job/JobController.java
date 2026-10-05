package com.queuelab.api.job;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    /** Crea el trabajo en {@code QUEUED} y responde de inmediato, sin esperar a que se procese. */
    @PostMapping
    ResponseEntity<JobResponse> submit(@RequestBody SubmitJobRequest request) {
        Job job = service.submit(request.type());
        var location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").build(job.id());
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

    @GetMapping("/{id}")
    JobResponse get(@PathVariable UUID id) {
        return JobResponse.from(service.get(id));
    }
}
