-- Idempotencia del envío: el cliente puede mandar una clave (cabecera Idempotency-Key) y repetir la
-- petición sin crear trabajos duplicados. request_fingerprint es el SHA-256 de la carga original y
-- permite detectar que la misma clave se reutiliza con una carga distinta.
ALTER TABLE jobs
    ADD COLUMN idempotency_key     varchar(255),
    ADD COLUMN request_fingerprint char(64),
    ADD CONSTRAINT jobs_idempotency_pair_chk
        CHECK ((idempotency_key IS NULL) = (request_fingerprint IS NULL));

-- Único solo entre los trabajos que traen clave (índice parcial: los demás no pagan nada).
CREATE UNIQUE INDEX jobs_idempotency_key_uq ON jobs (idempotency_key) WHERE idempotency_key IS NOT NULL;
