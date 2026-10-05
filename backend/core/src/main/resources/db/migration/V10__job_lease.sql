-- Lease de ejecución: mientras un worker procesa un trabajo (RUNNING) renueva este instante. Si vence,
-- el worker se considera caído y el trabajo puede replanificarse o darse por fallido.
-- NULL fuera de RUNNING.
ALTER TABLE jobs ADD COLUMN lease_expires_at timestamptz;

-- Los RUNNING anteriores a los leases no tienen ninguno: se tratan como ya vencidos, de modo que el
-- recuperador los resuelva en su primera pasada en vez de quedar atascados para siempre.
UPDATE jobs SET lease_expires_at = now() WHERE status = 'RUNNING';

-- El recuperador solo mira los RUNNING, ordenados por vencimiento (índice parcial: se mantiene pequeño).
CREATE INDEX jobs_running_lease_idx ON jobs (lease_expires_at) WHERE status = 'RUNNING';
