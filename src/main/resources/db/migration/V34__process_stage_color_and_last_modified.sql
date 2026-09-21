-- Color de etapa (UI) y metadatos de última edición en borrador.

ALTER TABLE app.process_stage_def
    ADD COLUMN IF NOT EXISTS color_key VARCHAR(16) NOT NULL DEFAULT 'blue';

ALTER TABLE app.process_definition
    ADD COLUMN IF NOT EXISTS last_modified_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS last_modified_by UUID;

UPDATE app.process_stage_def SET color_key = 'blue' WHERE color_key IS NULL OR color_key = '';
