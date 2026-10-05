-- Migración inicial (baseline) del esquema de QueueLab.
--
-- Fija el punto de partida del historial de Flyway. Las tablas de dominio se
-- añaden en migraciones posteriores (V2__..., V3__...). Nunca se edita una
-- migración ya aplicada: los cambios van siempre en una versión nueva.
SELECT 1;
