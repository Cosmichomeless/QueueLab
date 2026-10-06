/**
 * Configuración de entorno del dashboard.
 *
 * La URL de la API se resuelve **en tiempo de ejecución** para que la misma imagen de contenedor sirva en
 * cualquier entorno: el servidor lee `QUEUELAB_API_URL` al atender cada petición y la entrega al navegador
 * dentro de la página (`window.__QUEUELAB_API_URL__`, ver `runtimeConfigScript`). `NEXT_PUBLIC_API_URL` (valor
 * fijado al compilar) queda como alternativa para el desarrollo local.
 */

export const DEFAULT_API_URL = "http://localhost:8080";

declare global {
  interface Window {
    __QUEUELAB_API_URL__?: string;
  }
}

function normalize(url: string): string {
  return url.trim().replace(/\/+$/, "");
}

/** URL de la API según el entorno del proceso Node (`QUEUELAB_API_URL`, luego `NEXT_PUBLIC_API_URL`). */
export function serverApiUrl(): string {
  const value = process.env.QUEUELAB_API_URL || process.env.NEXT_PUBLIC_API_URL || DEFAULT_API_URL;
  return normalize(value);
}

/** URL de la API, sin barra final: la del servidor si se está renderizando allí, la inyectada en la página en el navegador. */
export function apiUrl(): string {
  if (typeof window !== "undefined" && window.__QUEUELAB_API_URL__) {
    return normalize(window.__QUEUELAB_API_URL__);
  }
  return serverApiUrl();
}

/** Script que publica la URL en el navegador. Escapa `<` para que ningún valor pueda cerrar la etiqueta `<script>`. */
export function runtimeConfigScript(url: string = serverApiUrl()): string {
  return `window.__QUEUELAB_API_URL__=${JSON.stringify(url).replace(/</g, "\\u003c")};`;
}
