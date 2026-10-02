-- Capa extraída (IA + expediente) sin BIESS ni overrides: permite conocer el origen de cada
-- variable y restaurar un campo corregido a mano. payload sigue siendo el JSON final renderizado.
ALTER TABLE app.minuta_draft
    ADD COLUMN IF NOT EXISTS datos_extraidos JSONB;

COMMENT ON COLUMN app.minuta_draft.datos_extraidos IS
    'Variables extraídas por IA / expediente antes de aplicar BIESS y overrides manuales.';
