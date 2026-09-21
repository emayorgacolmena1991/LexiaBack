-- LEXIA-18 R3: ChangeSet de parametrización (DRAFT → REVIEW → APPROVED → publicación).

CREATE TABLE app.process_config_change_set (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               UUID NOT NULL REFERENCES control.tenant (id),
    process_definition_id   UUID NOT NULL,
    case_type               VARCHAR(8) NOT NULL,
    status                  VARCHAR(16) NOT NULL
        CHECK (status IN ('DRAFT', 'REVIEW', 'APPROVED', 'REJECTED', 'PUBLISHED')),
    submit_comment          VARCHAR(2000),
    submitted_by            UUID,
    submitted_at            TIMESTAMPTZ,
    review_comment          VARCHAR(2000),
    reviewed_by             UUID,
    reviewed_at             TIMESTAMPTZ,
    publication_id          UUID,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by              UUID NOT NULL,
    FOREIGN KEY (process_definition_id, tenant_id)
        REFERENCES app.process_definition (id, tenant_id),
    FOREIGN KEY (publication_id) REFERENCES app.process_config_publication (id)
);

CREATE INDEX ix_process_config_change_set_active
    ON app.process_config_change_set (tenant_id, process_definition_id, status, created_at DESC);

INSERT INTO app.permission (id, code, name, module_code) VALUES
    ('f1000000-0000-7000-8000-000000000016', 'admin:proceso:aprobar', 'Aprobar ChangeSet de proceso', 'admin')
ON CONFLICT (code) DO NOTHING;

INSERT INTO app.role_permission (role_id, permission_id, tenant_id)
SELECT 'f2000000-0000-7000-8000-000000000001', id, 'b1000000-0000-7000-8000-000000000001'
FROM app.permission
WHERE code = 'admin:proceso:aprobar'
ON CONFLICT DO NOTHING;
