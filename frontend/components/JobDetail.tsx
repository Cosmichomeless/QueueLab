"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { ApiError, downloadHref, getJob, retryJob, type Job } from "@/lib/api";
import { formatBytes, formatDate, shortId } from "@/lib/format";
import { canRetry, isActive, POLL_INTERVAL_MS } from "@/lib/jobs";
import { StatusBadge } from "./StatusBadge";
import styles from "./JobDetail.module.css";

type State =
  | { phase: "loading" }
  | { phase: "error"; message: string; notFound: boolean }
  // `refreshError`: la última actualización falló, pero se conserva lo último que se sabe del trabajo.
  | { phase: "ready"; job: Job; refreshError: string | null };

const PROGRESS: Record<"QUEUED" | "RUNNING" | "RETRYING", string> = {
  QUEUED: "Esperando en la cola a que un worker lo recoja.",
  RUNNING: "Un worker lo está procesando.",
  RETRYING: "El intento anterior falló; se volverá a ejecutar.",
};

type Retry = { phase: "idle" } | { phase: "sending" } | { phase: "requested" } | { phase: "error"; message: string };

export function JobDetail({ id }: { id: string }) {
  const [state, setState] = useState<State>({ phase: "loading" });
  const [reloads, setReloads] = useState(0);
  const [retry, setRetry] = useState<Retry>({ phase: "idle" });

  // Pide el trabajo y, mientras no sea final, repite cada POLL_INTERVAL_MS. El cleanup cancela el temporizador y
  // descarta la respuesta en vuelo (cambio de id, recarga manual o salida de la página).
  useEffect(() => {
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | undefined;

    async function refresh() {
      try {
        const job = await getJob(id);
        if (cancelled) return;
        setState({ phase: "ready", job, refreshError: null });
        if (isActive(job.status)) timer = setTimeout(refresh, POLL_INTERVAL_MS);
      } catch (error) {
        if (cancelled) return;
        const message = error instanceof ApiError ? error.message : "Error inesperado";
        // 400: el identificador ni siquiera es un UUID, tampoco puede existir.
        const notFound = error instanceof ApiError && (error.status === 404 || error.status === 400);
        setState((previous) =>
          previous.phase === "ready" && !notFound
            ? { ...previous, refreshError: message }
            : { phase: "error", message, notFound },
        );
        // Un fallo transitorio con datos ya mostrados no corta el seguimiento; sin datos, lo decide el usuario.
        if (!notFound) timer = setTimeout(refresh, POLL_INTERVAL_MS);
      }
    }

    void refresh();
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [id, reloads]);

  function reload() {
    setState({ phase: "loading" });
    setReloads((n) => n + 1);
  }

  async function requestRetry() {
    if (retry.phase === "sending") return;
    setRetry({ phase: "sending" });
    try {
      const job = await retryJob(id);
      setState({ phase: "ready", job, refreshError: null });
      setRetry({ phase: "requested" });
    } catch (error) {
      setRetry({ phase: "error", message: error instanceof ApiError ? error.message : "Error inesperado" });
    }
    // Con éxito, el trabajo vuelve a estar activo y hay que retomar el seguimiento; con error (p. ej. 409 porque otro
    // ya lo reintentó) se vuelve a pedir para mostrar el estado real y no uno desfasado.
    setReloads((n) => n + 1);
  }

  return (
    <section aria-labelledby="job-title">
      <Link href="/jobs" className={styles.back}>
        ← Trabajos
      </Link>
      <h1 id="job-title" className={styles.title}>
        Trabajo {shortId(id)}
      </h1>

      {state.phase === "loading" && (
        <p role="status" className={styles.message}>
          Cargando trabajo…
        </p>
      )}

      {state.phase === "error" && (
        <div role="alert" className={`${styles.message} ${styles.error}`}>
          <p>
            {state.notFound
              ? "No existe ningún trabajo con ese identificador."
              : `No se pudo cargar el trabajo: ${state.message}`}
          </p>
          {!state.notFound && (
            <button type="button" onClick={reload}>
              Reintentar
            </button>
          )}
        </div>
      )}

      {state.phase === "ready" && (
        <Ready job={state.job} refreshError={state.refreshError} retry={retry} onRetry={requestRetry} />
      )}
    </section>
  );
}

function Ready({
  job,
  refreshError,
  retry,
  onRetry,
}: {
  job: Job;
  refreshError: string | null;
  retry: Retry;
  onRetry: () => void;
}) {
  const active = isActive(job.status);
  return (
    <>
      <div className={styles.statusLine} role="status" aria-live="polite">
        <StatusBadge status={job.status} />
        <span>
          {active
            ? `${PROGRESS[job.status as keyof typeof PROGRESS]} Se actualiza solo.`
            : job.status === "COMPLETED"
              ? "El trabajo ha terminado correctamente."
              : "El trabajo ha fallado."}
        </span>
      </div>

      {retry.phase === "error" && (
        <p role="alert" className={styles.retryError}>
          No se pudo reintentar: {retry.message}
        </p>
      )}

      {retry.phase === "requested" && job.status !== "FAILED" && (
        <p role="status" className={styles.notice}>
          Reintento solicitado: el trabajo ha vuelto a la cola con sus intentos a cero.
        </p>
      )}

      {refreshError && (
        <p role="alert" className={styles.warning}>
          No se pudo actualizar el estado ({refreshError}). Se sigue reintentando; lo mostrado puede estar desfasado.
        </p>
      )}

      <dl className={styles.facts}>
        <dt>ID</dt>
        <dd className={styles.mono}>{job.id}</dd>
        <dt>Tipo</dt>
        <dd>{job.type}</dd>
        <dt>Intentos</dt>
        <dd>{job.attempts}</dd>
        <dt>Creado</dt>
        <dd>
          <time dateTime={job.createdAt}>{formatDate(job.createdAt)}</time>
        </dd>
        <dt>Iniciado</dt>
        <dd>{job.startedAt ? <time dateTime={job.startedAt}>{formatDate(job.startedAt)}</time> : "—"}</dd>
        <dt>Finalizado</dt>
        <dd>{job.finishedAt ? <time dateTime={job.finishedAt}>{formatDate(job.finishedAt)}</time> : "—"}</dd>
      </dl>

      {job.status === "COMPLETED" && (
        <section aria-labelledby="result-title" className={styles.panel}>
          <h2 id="result-title">Resultado</h2>
          <p>{job.result ?? "Sin resultado."}</p>
          {job.resultFile && (
            <p>
              <a href={downloadHref(job.resultFile)} download={job.resultFile.name} className={styles.download}>
                Descargar {job.resultFile.name}
              </a>{" "}
              <span className={styles.muted}>
                ({job.resultFile.contentType}, {formatBytes(job.resultFile.size)})
              </span>
            </p>
          )}
        </section>
      )}

      {(job.status === "FAILED" || (job.status === "RETRYING" && job.error)) && (
        <section aria-labelledby="error-title" className={`${styles.panel} ${styles.failure}`}>
          <h2 id="error-title">{job.status === "FAILED" ? "Fallo" : "Último fallo"}</h2>
          <p>{job.error ?? "Sin detalle del error."}</p>
          {canRetry(job) && (
            <button type="button" onClick={onRetry} disabled={retry.phase === "sending"}>
              {retry.phase === "sending" ? "Reintentando…" : "Reintentar trabajo"}
            </button>
          )}
        </section>
      )}
    </>
  );
}
