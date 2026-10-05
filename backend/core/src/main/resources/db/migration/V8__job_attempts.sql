-- Intentos de ejecución reclamados por workers. Se incrementa de forma atómica al pasar a RUNNING.
ALTER TABLE jobs
    ADD COLUMN attempts integer NOT NULL DEFAULT 0,
    ADD CONSTRAINT jobs_attempts_chk CHECK (attempts >= 0);
