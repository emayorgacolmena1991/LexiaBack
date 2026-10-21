ALTER TABLE app.jobs ADD COLUMN IF NOT EXISTS idempotency_key TEXT;

CREATE UNIQUE INDEX IF NOT EXISTS ux_jobs_idempotencia
    ON app.jobs (tenant_id, tipo, idempotency_key)
    WHERE idempotency_key IS NOT NULL AND estado IN ('PENDIENTE', 'EN_PROCESO');
