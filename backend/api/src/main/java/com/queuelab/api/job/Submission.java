package com.queuelab.api.job;

import com.queuelab.core.job.Job;

/** Resultado de un envío: el trabajo y si se creó ahora ({@code true}) o ya existía por la misma clave. */
public record Submission(Job job, boolean created) {
}
