-- Checklist documental ECD (V1) configurable por tenant.

CREATE TABLE app.ecd_document_req (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES control.tenant (id),
    document_type_code  VARCHAR(64) NOT NULL,
    sort_order          INTEGER NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, document_type_code)
);

INSERT INTO app.ecd_document_req (tenant_id, document_type_code, sort_order) VALUES
    ('b1000000-0000-7000-8000-000000000001', 'TITULO_EJECUTIVO', 1)
ON CONFLICT (tenant_id, document_type_code) DO NOTHING;
