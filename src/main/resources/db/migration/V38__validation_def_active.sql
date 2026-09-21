ALTER TABLE app.validation_def
    ADD COLUMN IF NOT EXISTS active BOOLEAN NOT NULL DEFAULT TRUE;

UPDATE app.validation_def SET active = TRUE WHERE active IS NULL;
