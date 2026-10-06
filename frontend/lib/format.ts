import type { JobStatus } from "./api";

export const STATUS_LABELS: Record<JobStatus, string> = {
  QUEUED: "En cola",
  RUNNING: "En ejecución",
  RETRYING: "Reintentando",
  COMPLETED: "Completado",
  FAILED: "Fallido",
};

const dateFormat = new Intl.DateTimeFormat("es-ES", { dateStyle: "medium", timeStyle: "medium" });

export function formatDate(iso: string): string {
  return dateFormat.format(new Date(iso));
}

export function shortId(id: string): string {
  return id.slice(0, 8);
}
