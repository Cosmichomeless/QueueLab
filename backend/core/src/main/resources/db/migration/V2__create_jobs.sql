-- Trabajos asíncronos y su ciclo de vida.
-- Las transiciones válidas entre estados se aplican en la lógica de aplicación
-- (JobStatus); la base de datos garantiza que el estado sea siempre uno conocido.
CREATE TABLE jobs (
    id          uuid         PRIMARY KEY,
    type        varchar(100) NOT NULL,
    status      varchar(20)  NOT NULL,
    created_at  timestamptz  NOT NULL,
    updated_at  timestamptz  NOT NULL,
    started_at  timestamptz,
    finished_at timestamptz,
    CONSTRAINT jobs_status_check
        CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED', 'RETRYING'))
);

-- Listados por estado y orden de creación (paginación estable).
CREATE INDEX jobs_status_created_at_idx ON jobs (status, created_at, id);
