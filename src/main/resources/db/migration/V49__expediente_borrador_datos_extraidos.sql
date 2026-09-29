-- Estudio IA del paso 4 mientras el trámite sigue en borrador (sin legal_case).
ALTER TABLE app.expediente_borrador
    ADD COLUMN IF NOT EXISTS datos_extraidos JSONB;

COMMENT ON COLUMN app.expediente_borrador.datos_extraidos IS
    'ProcesarExpedienteCompletoResult (documentos, datos consolidados y dictamen).';
