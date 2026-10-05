-- Los trabajos que ya estaban en QUEUED antes de existir el outbox no tienen evento publicable.
-- Se les crea uno para cumplir la invariante «ningún trabajo QUEUED sin evento pendiente».
INSERT INTO outbox_events (id, job_id, event_type, payload, created_at)
SELECT gen_random_uuid(),
       j.id,
       'JOB_QUEUED',
       jsonb_build_object('version', 1, 'jobId', j.id),
       j.created_at
FROM jobs j
WHERE j.status = 'QUEUED'
  AND NOT EXISTS (SELECT 1 FROM outbox_events e WHERE e.job_id = j.id);
