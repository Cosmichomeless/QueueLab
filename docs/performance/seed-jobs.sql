-- Datos sintéticos para comparar planes de consulta (ver docs/performance/query-plans.md).
-- Uso: crear una base APARTE (nunca la de desarrollo), aplicar las migraciones hasta la versión que se quiera
-- medir y ejecutar este script. 1.000.000 de trabajos repartidos en 30 días:
--   ~96 % COMPLETED (la mayoría con fichero de resultado), ~2,5 % FAILED, y una cola pequeña y realista:
--   500 QUEUED, 100 RETRYING y 50 RUNNING (30 de ellos con el lease ya vencido).
-- Es determinista (hash del número de fila), así que dos ejecuciones dan el mismo reparto.
INSERT INTO jobs (id, type, status, created_at, updated_at, started_at, finished_at, attempts, input_ref, result_ref,
                  lease_expires_at)
SELECT gen_random_uuid(),
       CASE WHEN n % 5 = 0 THEN 'noop' ELSE 'csv-import' END,
       s.status,
       s.created,
       s.created,
       CASE WHEN s.status <> 'QUEUED' THEN s.created + interval '1 second' END,
       CASE WHEN s.status IN ('COMPLETED', 'FAILED') THEN s.created + interval '30 seconds' END,
       CASE WHEN s.status = 'QUEUED' THEN 0 ELSE 1 END,
       CASE WHEN n % 5 <> 0 AND s.status IN ('QUEUED', 'RUNNING', 'RETRYING', 'FAILED') THEN 'inputs/' || n || '.csv' END,
       CASE WHEN n % 5 <> 0 AND s.status = 'COMPLETED' THEN 'results/' || n || '.json' END,
       CASE WHEN s.status = 'RUNNING'
            THEN now() + (CASE WHEN n % 5 < 3 THEN -1 ELSE 1 END) * interval '1 minute' END
FROM (SELECT n,
             -- Los más recientes son los que están en cola o en curso; el resto, historia.
             CASE WHEN n > 1000000 - 500 THEN 'QUEUED'
                  WHEN n > 1000000 - 600 THEN 'RETRYING'
                  WHEN n > 1000000 - 650 THEN 'RUNNING'
                  WHEN hashint4(n) % 40 = 0 THEN 'FAILED'
                  ELSE 'COMPLETED' END AS status,
             now() - interval '30 days' + n * (interval '30 days' / 1000000) AS created
      FROM generate_series(1, 1000000) AS n) AS s;
ANALYZE jobs;
