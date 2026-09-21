-- LEXIA-18 R2: snapshots de publicación, gobierno de reglas y registro de plantillas.

ALTER TABLE app.process_config_publication
    ADD COLUMN IF NOT EXISTS snapshot_json TEXT;

ALTER TABLE app.rule_def DROP CONSTRAINT IF EXISTS rule_def_status_check;

ALTER TABLE app.rule_def
    ADD CONSTRAINT rule_def_status_check
        CHECK (status IN ('DRAFT', 'REVIEW', 'APPROVED', 'ACTIVE', 'SUSPENDED', 'RETIRED'));

CREATE TABLE app.template_def (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES control.tenant (id),
    code            VARCHAR(64) NOT NULL,
    version         INTEGER NOT NULL DEFAULT 1,
    name            VARCHAR(200) NOT NULL,
    vertical        VARCHAR(8) NOT NULL CHECK (vertical IN ('EJD', 'ECD')),
    body            TEXT,
    status          VARCHAR(24) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'REVIEW', 'APPROVED', 'ACTIVE', 'SUSPENDED', 'RETIRED')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id, tenant_id),
    UNIQUE (tenant_id, code, version)
);

INSERT INTO app.template_def (tenant_id, code, version, name, vertical, body, status) VALUES
    (
        'b1000000-0000-7000-8000-000000000001',
        'EJD.ACTA_ESTUDIO',
        1,
        'Acta de estudio de títulos (estructura demo)',
        'EJD',
        '{"sections":["encabezado","partes","inmueble","observaciones"]}',
        'ACTIVE'
    ),
    (
        'b1000000-0000-7000-8000-000000000001',
        'ECD.MEMORIAL',
        1,
        'Memorial coactivo (estructura demo)',
        'ECD',
        '{"sections":["encabezado","obligacion","medidas"]}',
        'ACTIVE'
    )
ON CONFLICT (tenant_id, code, version) DO NOTHING;

INSERT INTO app.permission (id, code, name, module_code) VALUES
    ('f1000000-0000-7000-8000-000000000017', 'admin:reglas:leer', 'Ver reglas y plantillas', 'admin'),
    ('f1000000-0000-7000-8000-000000000018', 'admin:reglas:escribir', 'Editar borradores de reglas', 'admin'),
    ('f1000000-0000-7000-8000-000000000019', 'admin:reglas:aprobar', 'Aprobar y activar reglas', 'admin')
ON CONFLICT (code) DO NOTHING;

INSERT INTO app.role_permission (role_id, permission_id, tenant_id)
SELECT 'f2000000-0000-7000-8000-000000000001', id, 'b1000000-0000-7000-8000-000000000001'
FROM app.permission
WHERE code IN ('admin:reglas:leer', 'admin:reglas:escribir', 'admin:reglas:aprobar')
ON CONFLICT DO NOTHING;
