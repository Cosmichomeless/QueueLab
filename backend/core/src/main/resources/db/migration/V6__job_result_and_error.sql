-- Resultado de un trabajo COMPLETED y error resumido de un trabajo FAILED, escritos por el worker.
-- `error` es un resumen seguro de mostrar por la API: nunca trazas ni datos internos.
ALTER TABLE jobs
    ADD COLUMN result varchar(1000),
    ADD COLUMN error  varchar(500);
