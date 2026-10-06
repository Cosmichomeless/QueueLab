-- Consultas de JobRepository que se comparan en docs/performance/query-plans.md, con valores representativos.
-- Uso: psql -d <base de pruebas> -f docs/performance/explain-queries.sql
\echo '== Q1 listado sin filtro, primera página (findPage)'
EXPLAIN (ANALYZE, BUFFERS, SUMMARY ON)
SELECT * FROM jobs WHERE true ORDER BY created_at DESC, id DESC LIMIT 21;

\echo '== Q2 listado sin filtro, página profunda con cursor (findPage)'
EXPLAIN (ANALYZE, BUFFERS, SUMMARY ON)
SELECT * FROM jobs WHERE true AND (created_at, id) < (now() - interval '15 days', 'ffffffff-ffff-ffff-ffff-ffffffffffff')
ORDER BY created_at DESC, id DESC LIMIT 21;

\echo '== Q3 listado filtrado por FAILED (25.000 filas)'
EXPLAIN (ANALYZE, BUFFERS, SUMMARY ON)
SELECT * FROM jobs WHERE true AND status = 'FAILED' ORDER BY created_at DESC, id DESC LIMIT 21;

\echo '== Q4 listado filtrado por QUEUED (500 filas)'
EXPLAIN (ANALYZE, BUFFERS, SUMMARY ON)
SELECT * FROM jobs WHERE true AND status = 'QUEUED' ORDER BY created_at DESC, id DESC LIMIT 21;

\echo '== Q5 trabajos pendientes acotados (countWaiting, contrapresión)'
EXPLAIN (ANALYZE, BUFFERS, SUMMARY ON)
SELECT count(*) FROM (SELECT 1 FROM jobs WHERE status IN ('QUEUED', 'RETRYING') LIMIT 1000) waiting;

\echo '== Q6 leases vencidos (findExpired, recuperador)'
EXPLAIN (ANALYZE, BUFFERS, SUMMARY ON)
SELECT * FROM jobs WHERE status = 'RUNNING' AND lease_expires_at <= now() ORDER BY lease_expires_at, id LIMIT 50;

\echo '== Q7 entradas liberables (findReleasableInputs, limpieza)'
EXPLAIN (ANALYZE, BUFFERS, SUMMARY ON)
SELECT id, input_ref AS ref FROM jobs
WHERE input_ref IS NOT NULL
  AND ((status = 'COMPLETED' AND finished_at < now() - interval '1 hour')
    OR (status = 'FAILED' AND finished_at < now() - interval '7 days'))
ORDER BY finished_at LIMIT 100;

\echo '== Q8 resultados liberables (findReleasableResults, limpieza)'
EXPLAIN (ANALYZE, BUFFERS, SUMMARY ON)
SELECT id, result_ref AS ref FROM jobs
WHERE result_ref IS NOT NULL AND status = 'COMPLETED' AND finished_at < now() - interval '7 days'
ORDER BY finished_at LIMIT 100;

\echo '== Q9 ¿algún trabajo referencia este fichero? (isReferenced, limpieza de huérfanos)'
EXPLAIN (ANALYZE, BUFFERS, SUMMARY ON)
SELECT EXISTS (SELECT 1 FROM jobs WHERE input_ref = 'inputs/999999999.csv' OR result_ref = 'inputs/999999999.csv');
