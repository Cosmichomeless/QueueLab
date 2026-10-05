-- Historial de reintentos manuales de un trabajo FAILED. Cada fila conserva cuántos intentos se habían
-- consumido y cuál fue el último error en el momento de reintentar; jobs.attempts vuelve a 0 para que el
-- nuevo intento tenga el presupuesto completo de reintentos automáticos.
CREATE TABLE job_retries (
    id           uuid         PRIMARY KEY,
    job_id       uuid         NOT NULL REFERENCES jobs (id) ON DELETE CASCADE,
    requested_at timestamptz  NOT NULL,
    attempts     integer      NOT NULL,
    error        varchar(500)
);

CREATE INDEX job_retries_job_id_requested_at_idx ON job_retries (job_id, requested_at, id);
