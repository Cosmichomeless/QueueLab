import type { Job } from "@/lib/api";

export const JOB_ID = "3f2b8c1e-5a4d-4e7b-9c1a-0d2e6f7a8b9c";

export function makeJob(overrides: Partial<Job> = {}): Job {
  return {
    id: JOB_ID,
    type: "CSV",
    status: "QUEUED",
    createdAt: "2026-10-06T10:00:00Z",
    updatedAt: "2026-10-06T10:00:00Z",
    startedAt: null,
    finishedAt: null,
    result: null,
    error: null,
    attempts: 0,
    resultFile: null,
    ...overrides,
  };
}

export function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": status >= 400 ? "application/problem+json" : "application/json" },
  });
}

export function problem(status: number, title: string, detail?: string): Response {
  return jsonResponse({ type: "about:blank", title, status, ...(detail ? { detail } : {}) }, status);
}

export function csvFile(content = "a,b\n1,2\n", name = "datos.csv", type = "text/csv"): File {
  return new File([content], name, { type });
}
