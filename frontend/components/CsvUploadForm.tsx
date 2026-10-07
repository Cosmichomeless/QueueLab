"use client";

import { useRouter } from "next/navigation";
import { useState, type ChangeEvent, type FormEvent } from "react";
import { ApiError, uploadCsv } from "@/lib/api";
import { MAX_CSV_BYTES, validateCsvFile } from "@/lib/csv";
import { formatBytes } from "@/lib/format";
import styles from "./CsvUploadForm.module.css";

export function CsvUploadForm() {
  const router = useRouter();
  const [file, setFile] = useState<File | null>(null);
  // El error de validación solo se enseña tras elegir un fichero o intentar enviar, no al abrir la página.
  const [touched, setTouched] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [apiError, setApiError] = useState<string | null>(null);

  const validationError = touched ? validateCsvFile(file) : null;

  function onChange(event: ChangeEvent<HTMLInputElement>) {
    setFile(event.target.files?.[0] ?? null);
    setTouched(true);
    setApiError(null);
  }

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setTouched(true);
    if (submitting || validateCsvFile(file) !== null || !file) return;
    setSubmitting(true);
    setApiError(null);
    try {
      const job = await uploadCsv(file);
      router.push(`/jobs/${job.id}`);
    } catch (error) {
      setApiError(error instanceof ApiError ? error.message : "Error inesperado al enviar el fichero.");
      setSubmitting(false);
    }
  }

  return (
    <section aria-labelledby="new-title">
      <h1 id="new-title">Nuevo trabajo CSV</h1>
      <p className={`${styles.help} ${styles.intro}`}>
        Sube un fichero CSV (UTF-8, con cabecera, hasta {formatBytes(MAX_CSV_BYTES)}). Se procesará en segundo plano y
        podrás seguir su estado.
      </p>
      <div className={styles.card}>
      <form onSubmit={onSubmit} noValidate className={styles.form}>
        <label htmlFor="csv-file">Fichero CSV</label>
        <input
          id="csv-file"
          name="file"
          type="file"
          accept=".csv,text/csv"
          onChange={onChange}
          disabled={submitting}
          aria-invalid={validationError !== null}
          aria-describedby={validationError ? "csv-file-error" : undefined}
        />
        {file && !validationError && (
          <p className={styles.help}>
            {file.name} · {formatBytes(file.size)}
          </p>
        )}
        {validationError && (
          <p id="csv-file-error" role="alert" className={styles.error}>
            {validationError}
          </p>
        )}
        {apiError && (
          <p role="alert" className={styles.error}>
            No se pudo enviar el fichero: {apiError}
          </p>
        )}
        <div>
          <button type="submit" className={styles.submit} disabled={submitting || (touched && validationError !== null)}>
            {submitting ? "Enviando…" : "Enviar"}
          </button>
        </div>
      </form>
      </div>
    </section>
  );
}
