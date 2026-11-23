-- Clasificación del tipo documental: automática (LLM sobre el OCR) o manual (PATCH .../tipo).
-- Una clasificación automática nunca pisa una MANUAL. Sin backfill: un documento que ya tiene
-- doc_type y no tiene origen se trata en código como fijado por el usuario.
-- app.document ya tiene RLS (V6); las columnas nuevas quedan cubiertas.
ALTER TABLE app.document
    ADD COLUMN tipo_sugerido           VARCHAR(80),
    ADD COLUMN clasificacion_confianza NUMERIC(4, 3),
    ADD COLUMN clasificacion_origen    VARCHAR(16),
    ADD COLUMN clasificacion_estado    VARCHAR(16) NOT NULL DEFAULT 'PENDIENTE',
    ADD COLUMN clasificacion_modelo    VARCHAR(80),
    ADD COLUMN clasificado_en          TIMESTAMPTZ;

ALTER TABLE app.document
    ADD CONSTRAINT ck_document_clasificacion_origen
        CHECK (clasificacion_origen IS NULL OR clasificacion_origen IN ('AUTO', 'MANUAL')),
    ADD CONSTRAINT ck_document_clasificacion_estado
        CHECK (clasificacion_estado IN ('PENDIENTE', 'CLASIFICADO', 'POR_REVISAR', 'ERROR')),
    ADD CONSTRAINT ck_document_clasificacion_confianza
        CHECK (clasificacion_confianza IS NULL OR clasificacion_confianza BETWEEN 0 AND 1);
