-- Borrador de actuación: payload final, capa IA+sistema y overrides del panel.

CREATE TABLE app.coactiva_documento_draft (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                   UUID NOT NULL REFERENCES control.tenant (id),
    expediente_id               UUID NOT NULL,
    plantilla_id                UUID NOT NULL,
    actuacion_tipo              VARCHAR(64) NOT NULL,
    payload                     JSONB,
    datos_extraidos             JSONB,
    overrides                   JSONB,
    storage_path                VARCHAR(1024),
    variables_pendientes_count  INT NOT NULL DEFAULT 0,
    status                      VARCHAR(24) NOT NULL DEFAULT 'BORRADOR'
        CHECK (status IN ('BORRADOR', 'LISTO')),
    actuacion_id                UUID,
    created_by                  UUID,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, expediente_id, actuacion_tipo),
    FOREIGN KEY (expediente_id, tenant_id) REFERENCES app.coactiva_expediente (id, tenant_id),
    FOREIGN KEY (plantilla_id, tenant_id) REFERENCES app.coactiva_plantilla (id, tenant_id)
);

COMMENT ON TABLE app.coactiva_documento_draft IS
    'Último borrador por expediente y código de plantilla. Precedencia: IA < sistema < overrides.';

ALTER TABLE app.coactiva_documento_draft ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS tenant_isolation ON app.coactiva_documento_draft;

CREATE POLICY tenant_isolation ON app.coactiva_documento_draft
    USING (tenant_id = app.current_tenant_id())
    WITH CHECK (tenant_id = app.current_tenant_id());

GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE app.coactiva_documento_draft TO lexia_app;
GRANT SELECT ON TABLE app.coactiva_documento_draft TO lexia_readonly;
