-- Borrador del paso 2. El id lo genera PostgreSQL (gen_random_uuid).
CREATE TABLE app.expediente_borrador (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES control.tenant (id),
    id_acto         VARCHAR(64),
    product_code    VARCHAR(64),
    canton          VARCHAR(64),
    ingestion_mode  VARCHAR(32) NOT NULL
        CHECK (ingestion_mode IN ('DIGITAL_SEPARADO', 'FISICO_ESCANEADO')),
    status          VARCHAR(24) NOT NULL DEFAULT 'BORRADOR',
    created_by      UUID,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_expediente_borrador_tenant ON app.expediente_borrador (tenant_id, created_at DESC);

ALTER TABLE app.expediente_borrador ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON app.expediente_borrador;
CREATE POLICY tenant_isolation ON app.expediente_borrador
    USING (tenant_id = app.current_tenant_id())
    WITH CHECK (tenant_id = app.current_tenant_id());

GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE app.expediente_borrador TO lexia_app;
GRANT SELECT ON TABLE app.expediente_borrador TO lexia_readonly;
