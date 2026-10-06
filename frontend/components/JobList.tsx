"use client";

import Link from "next/link";
import { useCallback, useEffect, useRef, useState } from "react";
import { ApiError, JOB_STATUSES, listJobs, type Job, type JobStatus } from "@/lib/api";
import { formatDate, shortId, STATUS_LABELS } from "@/lib/format";
import { StatusBadge } from "./StatusBadge";
import styles from "./JobList.module.css";

type State =
  | { phase: "loading" }
  | { phase: "error"; message: string }
  | { phase: "ready"; jobs: Job[]; nextCursor: string | null; loadingMore: boolean; moreError: string | null };

export function JobList() {
  const [status, setStatus] = useState<JobStatus | "">("");
  const [state, setState] = useState<State>({ phase: "loading" });
  const [reloads, setReloads] = useState(0);
  // Descarta respuestas de una petición que ya no es la vigente (cambio de filtro o recarga).
  const current = useRef(0);

  useEffect(() => {
    const request = ++current.current;
    listJobs({ status })
      .then((page) => {
        if (request === current.current) {
          setState({ phase: "ready", jobs: page.items, nextCursor: page.nextCursor, loadingMore: false, moreError: null });
        }
      })
      .catch((error: unknown) => {
        if (request === current.current) {
          setState({ phase: "error", message: error instanceof ApiError ? error.message : "Error inesperado" });
        }
      });
  }, [status, reloads]);

  const reload = useCallback(() => {
    setState({ phase: "loading" });
    setReloads((n) => n + 1);
  }, []);

  function changeStatus(next: JobStatus | "") {
    setState({ phase: "loading" });
    setStatus(next);
  }

  async function loadMore() {
    if (state.phase !== "ready" || !state.nextCursor || state.loadingMore) return;
    const request = current.current;
    setState({ ...state, loadingMore: true, moreError: null });
    try {
      const page = await listJobs({ status, cursor: state.nextCursor });
      if (request !== current.current) return;
      setState({ phase: "ready", jobs: [...state.jobs, ...page.items], nextCursor: page.nextCursor, loadingMore: false, moreError: null });
    } catch (error) {
      if (request !== current.current) return;
      setState({ ...state, loadingMore: false, moreError: error instanceof ApiError ? error.message : "Error inesperado" });
    }
  }

  return (
    <section aria-labelledby="jobs-title">
      <div className={styles.header}>
        <h1 id="jobs-title">Trabajos</h1>
        <div className={styles.controls}>
          <label>
            Estado{" "}
            <select value={status} onChange={(e) => changeStatus(e.target.value as JobStatus | "")}>
              <option value="">Todos</option>
              {JOB_STATUSES.map((s) => (
                <option key={s} value={s}>
                  {STATUS_LABELS[s]}
                </option>
              ))}
            </select>
          </label>
          <button type="button" onClick={reload} disabled={state.phase === "loading"}>
            Actualizar
          </button>
          <Link href="/jobs/new" className={styles.primary}>
            Nuevo CSV
          </Link>
        </div>
      </div>

      {state.phase === "loading" && (
        <p role="status" className={styles.message}>
          Cargando trabajos…
        </p>
      )}

      {state.phase === "error" && (
        <div role="alert" className={`${styles.message} ${styles.error}`}>
          <p>No se pudieron cargar los trabajos: {state.message}</p>
          <button type="button" onClick={reload}>
            Reintentar
          </button>
        </div>
      )}

      {state.phase === "ready" && state.jobs.length === 0 && (
        <p className={styles.message}>
          {status
            ? `No hay trabajos en estado «${STATUS_LABELS[status]}».`
            : "Todavía no hay trabajos. Cuando se cree el primero aparecerá aquí."}
        </p>
      )}

      {state.phase === "ready" && state.jobs.length > 0 && (
        <>
          <div className={styles.tableWrap}>
            <table className={styles.table}>
              <thead>
                <tr>
                  <th scope="col">ID</th>
                  <th scope="col">Tipo</th>
                  <th scope="col">Estado</th>
                  <th scope="col">Creado</th>
                </tr>
              </thead>
              <tbody>
                {state.jobs.map((job) => (
                  <tr key={job.id}>
                    <td>
                      <Link href={`/jobs/${job.id}`} className={styles.id} title={job.id}>
                        {shortId(job.id)}
                      </Link>
                    </td>
                    <td>{job.type}</td>
                    <td>
                      <StatusBadge status={job.status} />
                    </td>
                    <td>
                      <time dateTime={job.createdAt}>{formatDate(job.createdAt)}</time>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {state.moreError && (
            <p role="alert" className={styles.error}>
              No se pudieron cargar más trabajos: {state.moreError}
            </p>
          )}
          {state.nextCursor && (
            <button type="button" onClick={loadMore} disabled={state.loadingMore} className={styles.more}>
              {state.loadingMore ? "Cargando…" : "Cargar más"}
            </button>
          )}
        </>
      )}
    </section>
  );
}
