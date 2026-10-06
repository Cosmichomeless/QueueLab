-- Índices para la limpieza de ficheros (StorageCleaner). Sin ellos, cada consulta recorría toda la tabla jobs
-- (seq scan: 23-55 ms con 1.000.000 de trabajos y creciendo con la tabla; ver docs/performance/query-plans.md).
-- La paginación del listado, el conteo de pendientes y la búsqueda de leases vencidos ya usaban índices
-- adecuados (V2, V3 y V10) y no cambian.
--
-- Todos son parciales sobre referencias NO nulas: la limpieza pone la referencia a NULL al liberar el fichero,
-- así que el índice solo contiene lo que aún está por limpiar y se mantiene pequeño aunque la tabla crezca.

-- isReferenced: ¿algún trabajo apunta a este fichero? (input_ref = :ref OR result_ref = :ref → BitmapOr).
CREATE INDEX jobs_input_ref_idx ON jobs (input_ref) WHERE input_ref IS NOT NULL;
CREATE INDEX jobs_result_ref_idx ON jobs (result_ref) WHERE result_ref IS NOT NULL;

-- findReleasableInputs / findReleasableResults: lo más antiguo primero, solo trabajos terminados con fichero.
CREATE INDEX jobs_input_release_idx ON jobs (finished_at)
    WHERE input_ref IS NOT NULL AND status IN ('COMPLETED', 'FAILED');
CREATE INDEX jobs_result_release_idx ON jobs (finished_at)
    WHERE result_ref IS NOT NULL AND status = 'COMPLETED';
