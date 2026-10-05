-- Outbox transaccional: cada trabajo enviado guarda aquí, en la misma transacción, el evento que
-- hay que publicar en RabbitMQ. Un proceso aparte lo publica y lo marca (published_at), así que
-- una caída del broker nunca pierde un trabajo.
CREATE TABLE outbox_events (
    id           uuid         PRIMARY KEY,
    job_id       uuid         NOT NULL REFERENCES jobs (id) ON DELETE CASCADE,
    event_type   varchar(100) NOT NULL,
    payload      jsonb        NOT NULL,
    created_at   timestamptz  NOT NULL,
    published_at timestamptz,
    attempts     integer      NOT NULL DEFAULT 0,
    last_error   varchar(500)
);

-- Pendientes de publicar, en orden de creación (índice parcial: se mantiene pequeño).
CREATE INDEX outbox_events_pending_idx ON outbox_events (created_at, id) WHERE published_at IS NULL;

CREATE INDEX outbox_events_job_id_idx ON outbox_events (job_id);
