ALTER TABLE app.jobs ADD COLUMN IF NOT EXISTS prioridad INT NOT NULL DEFAULT 0;
CREATE INDEX IF NOT EXISTS ix_jobs_cola ON app.jobs (estado, run_at, prioridad DESC, created_at);

ALTER TABLE app.escrituracion_prompt_catalog ADD COLUMN IF NOT EXISTS version INT NOT NULL DEFAULT 1;

DO $$
DECLARE
    nombre TEXT;
BEGIN
    FOR nombre IN
        SELECT conname FROM pg_constraint
        WHERE conrelid = 'app.escrituracion_prompt_catalog'::regclass AND contype = 'u'
    LOOP
        EXECUTE format('ALTER TABLE app.escrituracion_prompt_catalog DROP CONSTRAINT %I', nombre);
    END LOOP;
END $$;

ALTER TABLE app.escrituracion_prompt_catalog
    ADD CONSTRAINT uq_escrituracion_prompt_catalog_version
    UNIQUE (canton, product_code, tipo_documento, version);

ALTER TABLE app.title_study ADD COLUMN IF NOT EXISTS prompt_version VARCHAR(120);
ALTER TABLE app.title_study ADD COLUMN IF NOT EXISTS modelo VARCHAR(80);
ALTER TABLE app.title_study ADD COLUMN IF NOT EXISTS documento_sha256 VARCHAR(64);
