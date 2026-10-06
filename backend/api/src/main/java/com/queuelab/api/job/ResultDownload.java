package com.queuelab.api.job;

import java.io.InputStream;

/** Resultado listo para descargar; quien lo reciba debe cerrar {@code content}. */
public record ResultDownload(ResultFile file, InputStream content) {
}
