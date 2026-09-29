-- Expediente oficial creado al salir del paso 4. Idempotente por borrador.
ALTER TABLE app.expediente_borrador
    ADD COLUMN IF NOT EXISTS promoted_case_id UUID;

COMMENT ON COLUMN app.expediente_borrador.promoted_case_id IS
    'legal_case creado al promover el borrador. Reutilizado en llamadas siguientes.';
