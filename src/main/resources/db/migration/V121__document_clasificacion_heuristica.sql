-- Origen HEURISTICA: tipo provisional (nombre de archivo / ingesta) sobrescribible por IA tras OCR.
ALTER TABLE app.document
    DROP CONSTRAINT IF EXISTS ck_document_clasificacion_origen;

ALTER TABLE app.document
    ADD CONSTRAINT ck_document_clasificacion_origen
        CHECK (clasificacion_origen IS NULL OR clasificacion_origen IN ('AUTO', 'MANUAL', 'HEURISTICA'));

ALTER TABLE app.document_classification
    ADD COLUMN IF NOT EXISTS source VARCHAR(16),
    ADD COLUMN IF NOT EXISTS ocr_excerpt VARCHAR(500);

COMMENT ON COLUMN app.document_classification.source IS 'MANUAL, AUTO o HEURISTICA al registrar la etiqueta.';
COMMENT ON COLUMN app.document_classification.ocr_excerpt IS 'Fragmento OCR usado o corregido (máx. 500).';