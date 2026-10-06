-- Coste en escritura de los índices de V13 (job_file_reference_indexes), medido de forma aislada.
--
-- Simula el ciclo de vida de 200.000 trabajos CSV (aceptar → RUNNING → COMPLETED) sobre dos tablas temporales:
--   · jobs_base: solo los índices anteriores a V13 (pkey, created_at, status+created_at, lease, idempotency).
--   · jobs_v13 : los mismos + los 4 índices parciales de V13.
-- Es una tabla temporal de la sesión: no toca la tabla `jobs` real. Se ejecuta sobre una base con V1–V13 aplicadas:
--   docker compose exec -T postgres psql -U queuelab -d queuelab -f - < docs/performance/write-cost.sql
-- Cada fase se repite en orden alterno (base, v13, v13, base, ...) para no favorecer a ninguna por caché o autovacuum.

\set ON_ERROR_STOP on
SET client_min_messages = notice;

CREATE TEMP TABLE jobs_base (LIKE jobs INCLUDING DEFAULTS INCLUDING CONSTRAINTS);
CREATE TEMP TABLE jobs_v13  (LIKE jobs INCLUDING DEFAULTS INCLUDING CONSTRAINTS);

DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['jobs_base', 'jobs_v13'] LOOP
    EXECUTE format('ALTER TABLE %I ADD PRIMARY KEY (id)', t);
    EXECUTE format('CREATE INDEX ON %I (created_at, id)', t);
    EXECUTE format('CREATE INDEX ON %I (status, created_at, id)', t);
    EXECUTE format('CREATE INDEX ON %I (lease_expires_at) WHERE status = ''RUNNING''', t);
    EXECUTE format('CREATE UNIQUE INDEX ON %I (idempotency_key) WHERE idempotency_key IS NOT NULL', t);
  END LOOP;
  CREATE INDEX ON jobs_v13 (input_ref) WHERE input_ref IS NOT NULL;
  CREATE INDEX ON jobs_v13 (result_ref) WHERE result_ref IS NOT NULL;
  CREATE INDEX ON jobs_v13 (finished_at) WHERE input_ref IS NOT NULL AND status IN ('COMPLETED', 'FAILED');
  CREATE INDEX ON jobs_v13 (finished_at) WHERE result_ref IS NOT NULL AND status = 'COMPLETED';
END $$;

CREATE TEMP TABLE results (variant text, phase text, round int, millis numeric);

DO $$
DECLARE
  variants text[] := ARRAY['jobs_base', 'jobs_v13', 'jobs_v13', 'jobs_base', 'jobs_base', 'jobs_v13'];
  v text; r int; t0 timestamptz;
  n constant int := 200000;
BEGIN
  FOR r IN 1..array_length(variants, 1) LOOP
    v := variants[r];
    EXECUTE format('TRUNCATE %I', v);
    -- Fase 1: aceptar el CSV (INSERT con input_ref)
    t0 := clock_timestamp();
    EXECUTE format($q$INSERT INTO %I (id, type, status, created_at, updated_at, attempts, input_ref)
      SELECT gen_random_uuid(), 'csv-import', 'QUEUED', now() + (g || ' ms')::interval, now(), 0, 'inputs/' || g || '.csv'
      FROM generate_series(1, %s) g$q$, v, n);
    INSERT INTO results VALUES (v, '1 insert', r, extract(epoch FROM clock_timestamp() - t0) * 1000);
    -- Fase 2: el worker reclama el trabajo (QUEUED → RUNNING con lease)
    t0 := clock_timestamp();
    EXECUTE format($q$UPDATE %I SET status = 'RUNNING', started_at = now(), lease_expires_at = now() + interval '30 s', attempts = 1$q$, v);
    INSERT INTO results VALUES (v, '2 claim', r, extract(epoch FROM clock_timestamp() - t0) * 1000);
    -- Fase 3: se completa con result_ref
    t0 := clock_timestamp();
    EXECUTE format($q$UPDATE %I SET status = 'COMPLETED', finished_at = now(), lease_expires_at = NULL,
      result_ref = replace(input_ref, 'inputs/', 'results/') || '.json'$q$, v);
    INSERT INTO results VALUES (v, '3 complete', r, extract(epoch FROM clock_timestamp() - t0) * 1000);
    -- Fase 4: la limpieza libera los ficheros (referencias a NULL)
    t0 := clock_timestamp();
    EXECUTE format($q$UPDATE %I SET input_ref = NULL, result_ref = NULL$q$, v);
    INSERT INTO results VALUES (v, '4 release', r, extract(epoch FROM clock_timestamp() - t0) * 1000);
  END LOOP;
END $$;

-- Mediana de 3 rondas por variante y fase (ms para 200.000 filas) y coste relativo.
SELECT phase,
       round((percentile_cont(0.5) WITHIN GROUP (ORDER BY millis) FILTER (WHERE variant = 'jobs_base'))::numeric) AS base_ms,
       round((percentile_cont(0.5) WITHIN GROUP (ORDER BY millis) FILTER (WHERE variant = 'jobs_v13'))::numeric) AS v13_ms,
       round((percentile_cont(0.5) WITHIN GROUP (ORDER BY millis) FILTER (WHERE variant = 'jobs_v13')
            / percentile_cont(0.5) WITHIN GROUP (ORDER BY millis) FILTER (WHERE variant = 'jobs_base') - 1)::numeric * 100) AS extra_pct
FROM results GROUP BY phase ORDER BY phase;
