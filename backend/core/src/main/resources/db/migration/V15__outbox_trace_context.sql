-- Contexto de la traza (W3C traceparent) del span en el que se guardó el evento: une la publicación y la
-- ejecución del trabajo con la petición HTTP o el intento del worker que lo originó. NULL en eventos anteriores.
ALTER TABLE outbox_events ADD COLUMN trace_context varchar(55);
