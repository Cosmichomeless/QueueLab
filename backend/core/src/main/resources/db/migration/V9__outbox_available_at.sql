-- Instante a partir del cual el dispatcher puede publicar el evento. NULL = de inmediato. Los
-- reintentos con espera exponencial insertan aquí un evento «JOB_QUEUED» con available_at futuro.
ALTER TABLE outbox_events ADD COLUMN available_at timestamptz;
