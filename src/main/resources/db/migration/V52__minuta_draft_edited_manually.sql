ALTER TABLE app.minuta_draft
    ADD COLUMN IF NOT EXISTS edited_manually BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN app.minuta_draft.edited_manually IS
    'El DOCX guardado tiene ediciones manuales: no se re-renderiza desde la plantilla.';
