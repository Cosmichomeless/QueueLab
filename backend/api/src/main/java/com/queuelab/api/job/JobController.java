package com.queuelab.api.job;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.queuelab.core.job.Job;

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

    @GetMapping("/{id}")
    JobResponse get(@PathVariable UUID id) {
        return JobResponse.from(service.get(id));
    }
}
