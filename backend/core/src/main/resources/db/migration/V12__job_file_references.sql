-- Referencias a ficheros en el almacenamiento (ver FileStorage). La base de datos nunca guarda el
-- contenido: solo la referencia opaca que devolvió el almacenamiento (p. ej. inputs/<id>.csv).
ALTER TABLE jobs ADD COLUMN input_ref  varchar(255);
ALTER TABLE jobs ADD COLUMN result_ref varchar(255);
