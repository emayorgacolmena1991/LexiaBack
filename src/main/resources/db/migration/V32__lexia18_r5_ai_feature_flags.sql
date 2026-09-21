-- LEXIA-18 R5: políticas de IA y feature flags gobernados por tenant.

CREATE TABLE app.tenant_ai_policy (
    tenant_id                 UUID PRIMARY KEY REFERENCES control.tenant (id),
    document_extraction       BOOLEAN NOT NULL DEFAULT TRUE,
    workspace_assist          BOOLEAN NOT NULL DEFAULT TRUE,
    routing_mode              VARCHAR(16) NOT NULL DEFAULT 'GEMINI'
        CHECK (routing_mode IN ('DEMO', 'GEMINI')),
    max_daily_document_jobs   INT NOT NULL DEFAULT 200
        CHECK (max_daily_document_jobs BETWEEN 1 AND 100000),
    min_confidence_percent    SMALLINT NOT NULL DEFAULT 70
        CHECK (min_confidence_percent BETWEEN 0 AND 100),
    jobs_today_count          INT NOT NULL DEFAULT 0,
    jobs_today_date           DATE,
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by                UUID
);

CREATE TABLE app.tenant_feature_flag (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES control.tenant (id),
    code            VARCHAR(64) NOT NULL,
    enabled         BOOLEAN NOT NULL DEFAULT FALSE,
    description     VARCHAR(500) NOT NULL,
    expires_at      TIMESTAMPTZ,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by      UUID,
    UNIQUE (tenant_id, code)
);

INSERT INTO app.tenant_ai_policy (tenant_id)
VALUES ('b1000000-0000-7000-8000-000000000001')
ON CONFLICT (tenant_id) DO NOTHING;

INSERT INTO app.tenant_feature_flag (tenant_id, code, enabled, description) VALUES
    ('b1000000-0000-7000-8000-000000000001', 'DOCUMENT_AI_EXTRACTION', TRUE,
     'Extracción OCR/IA al crear expediente (nuevo expediente).'),
    ('b1000000-0000-7000-8000-000000000001', 'WORKSPACE_AI_ASSIST', TRUE,
     'Indicadores de asistencia IA en el workspace.'),
    ('b1000000-0000-7000-8000-000000000001', 'CONNECTOR_OUTBOX_LIVE', FALSE,
     'Despacho live de conectores (requiere gateway configurado).')
ON CONFLICT (tenant_id, code) DO NOTHING;
