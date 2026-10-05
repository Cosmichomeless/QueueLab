-- Listado de trabajos sin filtro: paginación por cursor ordenada por (created_at, id).
-- Con filtro por estado se usa jobs_status_created_at_idx (V2).
CREATE INDEX jobs_created_at_idx ON jobs (created_at, id);
