-- TICKET-BE-503: el borrador con DOCX renderizado pasa de READY a DRAFT_GENERATED.
UPDATE app.minuta_draft
SET status = 'DRAFT_GENERATED'
WHERE status = 'READY';

COMMENT ON COLUMN app.minuta_draft.status IS
    'DRAFT (sin archivo) | DRAFT_GENERATED (DOCX renderizado desde plantilla; editable y descargable).';
