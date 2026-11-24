-- Número de instancia y estatus del proceso (seguimiento del bufete). V114 es la última de esta rama.

ALTER TABLE app.legal_case
    ADD COLUMN IF NOT EXISTS numero_instancia VARCHAR(80);

COMMENT ON COLUMN app.legal_case.numero_instancia IS
    'Número de instancia de proceso que registra el bufete. No es el código LEX.';

CREATE TABLE app.estatus_proceso (
    id          UUID PRIMARY KEY,
    tenant_id   UUID NOT NULL,
    case_id     UUID NOT NULL,
    etapa       VARCHAR(80) NOT NULL,
    texto       TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by  UUID
);

CREATE INDEX estatus_proceso_case_idx
    ON app.estatus_proceso (tenant_id, case_id, created_at DESC);

ALTER TABLE app.estatus_proceso ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS tenant_isolation ON app.estatus_proceso;

CREATE POLICY tenant_isolation ON app.estatus_proceso
    USING (tenant_id = app.current_tenant_id())
    WITH CHECK (tenant_id = app.current_tenant_id());

GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE app.estatus_proceso TO lexia_app;
GRANT SELECT ON TABLE app.estatus_proceso TO lexia_readonly;
