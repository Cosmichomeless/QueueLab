/** Cliente de la API de QueueLab (ver backend/README.md, «API de trabajos»). */

export const API_URL = (process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080").replace(/\/+$/, "");

export const JOB_STATUSES = ["QUEUED", "RUNNING", "RETRYING", "COMPLETED", "FAILED"] as const;
export type JobStatus = (typeof JOB_STATUSES)[number];

export interface ResultFile {
  name: string;
  contentType: string;
  size: number;
  downloadUrl: string;
}

export interface Job {
  id: string;
  type: string;
  status: JobStatus;
  createdAt: string;
  updatedAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  result: string | null;
  error: string | null;
  attempts: number;
  resultFile: ResultFile | null;
}

export interface JobPage {
  items: Job[];
  nextCursor: string | null;
}

/** Error de la API (`application/problem+json`) o de red, con un mensaje apto para mostrar. */
export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number | null,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

export async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let response: Response;
  try {
    response = await fetch(`${API_URL}${path}`, { cache: "no-store", ...init });
  } catch {
    throw new ApiError("No se pudo conectar con la API. Comprueba que está en marcha.", null);
  }
  if (!response.ok) {
    throw new ApiError(await problemMessage(response), response.status);
  }
  return (await response.json()) as T;
}

async function problemMessage(response: Response): Promise<string> {
  try {
    const problem = (await response.json()) as { detail?: string; title?: string };
    return problem.detail ?? problem.title ?? `Error ${response.status}`;
  } catch {
    return `Error ${response.status}`;
  }
}

export function listJobs(options: { cursor?: string | null; status?: JobStatus | "" ; limit?: number } = {}) {
  const params = new URLSearchParams({ limit: String(options.limit ?? 20) });
  if (options.cursor) params.set("cursor", options.cursor);
  if (options.status) params.set("status", options.status);
  return request<JobPage>(`/api/v1/jobs?${params}`);
}

/** Sube un CSV (`multipart/form-data`, parte `file`) y devuelve el trabajo creado, en `QUEUED`. */
export function uploadCsv(file: File) {
  const body = new FormData();
  body.append("file", file);
  // Sin Content-Type: el navegador añade el multipart con su boundary.
  return request<Job>("/api/v1/jobs/csv", { method: "POST", body });
}

export function getJob(id: string) {
  return request<Job>(`/api/v1/jobs/${encodeURIComponent(id)}`);
}

/** URL absoluta de descarga del resultado (la API devuelve la ruta relativa en `downloadUrl`). */
export function downloadHref(file: ResultFile): string {
  return `${API_URL}${file.downloadUrl}`;
}
