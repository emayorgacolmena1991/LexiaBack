-- Duracion de ciclo de expediente (escrituracion y coactiva) para reportes e indicadores.

ALTER TABLE app.legal_case
    ADD COLUMN IF NOT EXISTS completed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS completion_outcome VARCHAR(32);

CREATE INDEX IF NOT EXISTS ix_legal_case_completed
    ON app.legal_case (tenant_id, completed_at DESC)
    WHERE completed_at IS NOT NULL AND deleted_at IS NULL;

CREATE TABLE IF NOT EXISTS app.case_cycle_snapshot (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES control.tenant (id),
    case_id         UUID NOT NULL,
    case_code       VARCHAR(40) NOT NULL,
    vertical        VARCHAR(64) NOT NULL,
    started_at      TIMESTAMPTZ NOT NULL,
    completed_at    TIMESTAMPTZ NOT NULL,
    outcome         VARCHAR(32) NOT NULL,
    duration_seconds BIGINT NOT NULL,
    stage_breakdown JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (case_id, tenant_id),
    FOREIGN KEY (case_id, tenant_id) REFERENCES app.legal_case (id, tenant_id)
);

CREATE INDEX IF NOT EXISTS ix_case_cycle_snapshot_tenant_completed
    ON app.case_cycle_snapshot (tenant_id, completed_at DESC);