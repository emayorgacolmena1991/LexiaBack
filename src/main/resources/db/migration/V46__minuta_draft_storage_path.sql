-- Minutas DOCX: ruta de archivo generado en disco.
ALTER TABLE app.minuta_draft
    ADD COLUMN IF NOT EXISTS storage_path VARCHAR(1024);

COMMENT ON COLUMN app.minuta_draft.storage_path IS
    'Ruta absoluta del .docx generado (poi-tl). READY cuando no es null.';
