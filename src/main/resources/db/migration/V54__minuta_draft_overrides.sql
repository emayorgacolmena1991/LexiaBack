-- TICKET-INT-102 [A]: overrides manuales del panel de variables (JSON tag -> valor).
-- Precedencia al consolidar: extracción LLM < captura BIESS < overrides manuales.
ALTER TABLE app.minuta_draft
    ADD COLUMN IF NOT EXISTS overrides JSONB;

COMMENT ON COLUMN app.minuta_draft.overrides IS
    'Variables corregidas/llenadas a mano en la previsualización (tag canónico -> valor). Mandan sobre LLM y BIESS.';
