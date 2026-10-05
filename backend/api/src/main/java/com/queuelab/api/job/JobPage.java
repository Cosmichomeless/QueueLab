package com.queuelab.api.job;

import java.util.List;

/**
 * Página de resultados.
 *
 * @param nextCursor valor opaco para pedir la página siguiente, o {@code null} si no hay más
 */
public record JobPage(List<JobResponse> items, String nextCursor) {
}
