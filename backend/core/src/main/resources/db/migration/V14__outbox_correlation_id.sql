-- Id de correlación: une el evento con la petición HTTP que lo originó y viaja como cabecera del mensaje
-- hasta el worker, para seguir un trabajo en los logs de API, publicación y worker. Nulo en los eventos
-- anteriores a esta migración (el despachador les asigna uno al publicar).
ALTER TABLE outbox_events ADD COLUMN correlation_id varchar(64);
