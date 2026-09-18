-- Workflow, integraciones, evidencia, jobs de procesamiento y RLS.

CREATE TABLE app.document_classification (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES control.tenant (id),
    document_id     UUID NOT NULL,
    label           VARCHAR(80) NOT NULL,
    assigned_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id, tenant_id),
    FOREIGN KEY (document_id, tenant_id) REFERENCES app.document (id, tenant_id)
);

CREATE TABLE app.processing_job (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES control.tenant (id),
    document_version_id UUID NOT NULL,
    job_type        VARCHAR(32) NOT NULL CHECK (job_type IN ('OCR', 'DOCUMENT_AI')),
    status          VARCHAR(24) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    detail          TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at     TIMESTAMPTZ,
    UNIQUE (id, tenant_id),
    FOREIGN KEY (document_version_id, tenant_id) REFERENCES app.document_version (id, tenant_id)
);

CREATE TABLE app.evidence (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES control.tenant (id),
    document_version_id UUID NOT NULL,
    case_id         UUID,
    validation_id   UUID,
    decision_id     UUID,
    action_id       UUID,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id, tenant_id),
    FOREIGN KEY (document_version_id, tenant_id) REFERENCES app.document_version (id, tenant_id),
    FOREIGN KEY (case_id, tenant_id) REFERENCES app.legal_case (id, tenant_id),
    FOREIGN KEY (validation_id, tenant_id) REFERENCES app.case_validation (id, tenant_id),
    FOREIGN KEY (decision_id, tenant_id) REFERENCES app.human_decision (id, tenant_id),
    FOREIGN KEY (action_id, tenant_id) REFERENCES app.case_action (id, tenant_id)
);

CREATE TABLE app.workflow_definition (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES control.tenant (id),
    code            VARCHAR(64) NOT NULL,
    name            VARCHAR(160) NOT NULL,
    body            TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id, tenant_id),
    UNIQUE (tenant_id, code)
);

CREATE TABLE app.workflow_instance (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES control.tenant (id),
    definition_id   UUID NOT NULL,
    case_id         UUID,
    status          VARCHAR(24) NOT NULL DEFAULT 'RUNNING',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id, tenant_id),
    FOREIGN KEY (definition_id, tenant_id) REFERENCES app.workflow_definition (id, tenant_id),
    FOREIGN KEY (case_id, tenant_id) REFERENCES app.legal_case (id, tenant_id)
);

CREATE TABLE app.integration (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES control.tenant (id),
    code            VARCHAR(64) NOT NULL
        CHECK (code IN ('QUIPUX', 'NOTARIA', 'MUNICIPIO', 'REGISTRO', 'FIRMA', 'PAGOS')),
    name            VARCHAR(160) NOT NULL,
    enabled         BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id, tenant_id),
    UNIQUE (tenant_id, code)
);

CREATE TABLE app.integration_credential (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES control.tenant (id),
    integration_id  UUID NOT NULL,
    secret_ref      VARCHAR(255) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id, tenant_id),
    FOREIGN KEY (integration_id, tenant_id) REFERENCES app.integration (id, tenant_id)
);

CREATE TABLE app.integration_call (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES control.tenant (id),
    integration_id  UUID NOT NULL,
    direction       VARCHAR(16) NOT NULL CHECK (direction IN ('OUTBOUND', 'INBOUND')),
    idempotency_key VARCHAR(128) NOT NULL,
    request_redacted TEXT,
    response_redacted TEXT,
    status          VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id, tenant_id),
    UNIQUE (tenant_id, integration_id, idempotency_key),
    FOREIGN KEY (integration_id, tenant_id) REFERENCES app.integration (id, tenant_id)
);

CREATE TABLE app.outbox_event (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES control.tenant (id),
    event_type      VARCHAR(96) NOT NULL,
    payload         TEXT NOT NULL,
    published_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id, tenant_id)
);

CREATE INDEX ix_outbox_unpublished ON app.outbox_event (tenant_id, created_at)
    WHERE published_at IS NULL;

-- RLS: el dueño (lexia_migrator) bypasea; lexia_app no.
DO $$
DECLARE
    tbl TEXT;
BEGIN
    FOR tbl IN
        SELECT format('%I.%I', n.nspname, c.relname)
        FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = 'tenant_id' AND NOT a.attisdropped
        WHERE n.nspname = 'app'
          AND c.relkind = 'r'
    LOOP
        EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY', tbl);
        EXECUTE format(
            'DROP POLICY IF EXISTS tenant_isolation ON %s',
            tbl
        );
        EXECUTE format(
            'CREATE POLICY tenant_isolation ON %s
                USING (tenant_id = app.current_tenant_id())
                WITH CHECK (tenant_id = app.current_tenant_id())',
            tbl
        );
    END LOOP;
END;
$$;

-- audit_event permite tenant_id NULL (eventos de plataforma) y lectura del propio tenant
DROP POLICY IF EXISTS tenant_isolation ON app.audit_event;
CREATE POLICY tenant_isolation ON app.audit_event
    USING (tenant_id IS NULL OR tenant_id = app.current_tenant_id())
    WITH CHECK (tenant_id IS NULL OR tenant_id = app.current_tenant_id());

GRANT SELECT ON ALL TABLES IN SCHEMA control TO lexia_app, lexia_readonly;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA app TO lexia_app;
GRANT SELECT ON ALL TABLES IN SCHEMA app TO lexia_readonly;
REVOKE UPDATE, DELETE ON app.audit_event FROM lexia_app;
GRANT SELECT, INSERT ON app.audit_event TO lexia_app;
