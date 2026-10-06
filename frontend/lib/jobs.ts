import type { JobStatus } from "./api";

/** Cada cuánto se vuelve a pedir un trabajo que aún no ha terminado. */
export const POLL_INTERVAL_MS = 2000;

/** `true` mientras el trabajo puede seguir cambiando (aún no es `COMPLETED` ni `FAILED`). */
export function isActive(status: JobStatus): boolean {
  return status === "QUEUED" || status === "RUNNING" || status === "RETRYING";
}
