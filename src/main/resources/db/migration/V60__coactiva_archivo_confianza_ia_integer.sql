-- Hibernate mapea CoactivaArchivo.confianzaIa (Integer) a INTEGER.
-- V59 lo creó como SMALLINT y ddl-auto=validate aborta el arranque.
ALTER TABLE app.coactiva_archivo
    ALTER COLUMN confianza_ia TYPE INTEGER;
