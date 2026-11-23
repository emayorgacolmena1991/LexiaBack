-- Bufete (tenant A) ↔ Notaría (tenant B). Registro en control; preferencias del bufete en app (RLS).
-- Sin APIs productivas en esta migración: solo estructura y semilla demo opcional.

ALTER TABLE control.tenant
    ADD COLUMN IF NOT EXISTS tenant_kind VARCHAR(32) NOT NULL DEFAULT 'LEGAL_FIRM';

ALTER TABLE control.tenant
    DROP CONSTRAINT IF EXISTS tenant_tenant_kind_check;

ALTER TABLE control.tenant
    ADD CONSTRAINT tenant_tenant_kind_check
        CHECK (tenant_kind IN ('LEGAL_FIRM', 'NOTARY', 'PLATFORM', 'OTHER'));

COMMENT ON COLUMN control.tenant.tenant_kind IS
    'LEGAL_FIRM = bufete/escrituración; NOTARY = operador notarial (siempre tenant separado).';

-- Perfil público-operativo de tenants tipo NOTARY (catálogo / bandeja).
CREATE TABLE control.notary_tenant_profile (
    tenant_id           UUID PRIMARY KEY REFERENCES control.tenant (id),
    ruc                 VARCHAR(20) NOT NULL,
    canton              VARCHAR(120) NOT NULL,
    notary_number       VARCHAR(32),
    official_name       VARCHAR(240) NOT NULL,
    notary_holder_name  VARCHAR(200),
    contact_email       VARCHAR(320),
    listed              BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_notary_profile_ruc ON control.notary_tenant_profile (ruc);
CREATE INDEX ix_notary_profile_canton ON control.notary_tenant_profile (canton, listed);

COMMENT ON TABLE control.notary_tenant_profile IS
    'Metadatos de notarías como tenants LEXIA. Visible en catálogo del bufete tras partnership ACTIVE.';

-- Vínculo formal bufete ↔ notaría (sin RLS; el API valida membresía en firm o notary).
CREATE TABLE control.tenant_notary_partnership (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    firm_tenant_id          UUID NOT NULL REFERENCES control.tenant (id),
    notary_tenant_id        UUID NOT NULL REFERENCES control.tenant (id),
    status                  VARCHAR(24) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'ACTIVE', 'SUSPENDED', 'REJECTED')),
    initiated_by_tenant_id  UUID NOT NULL REFERENCES control.tenant (id),
    notary_contact_email    VARCHAR(320),
    approved_at             TIMESTAMPTZ,
    suspended_at            TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (firm_tenant_id, notary_tenant_id),
    CHECK (firm_tenant_id <> notary_tenant_id)
);

CREATE INDEX ix_partnership_firm ON control.tenant_notary_partnership (firm_tenant_id, status);
CREATE INDEX ix_partnership_notary ON control.tenant_notary_partnership (notary_tenant_id, status);

-- Notarías habilitadas por un bufete (asignación / preferencias locales).
CREATE TABLE app.notary_firm_assignment (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES control.tenant (id),
    notary_tenant_id    UUID NOT NULL REFERENCES control.tenant (id),
    partnership_id      UUID NOT NULL REFERENCES control.tenant_notary_partnership (id),
    active              BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id, tenant_id),
    UNIQUE (tenant_id, notary_tenant_id),
    CHECK (tenant_id <> notary_tenant_id)
);

CREATE INDEX ix_notary_firm_assignment_tenant ON app.notary_firm_assignment (tenant_id, active);

ALTER TABLE app.notary_firm_assignment ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON app.notary_firm_assignment
    USING (tenant_id = app.current_tenant_id());

-- Remisión cross-tenant (autoridad en control; caso y documentos viven en tenant bufete).
CREATE TABLE control.notary_remision (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    firm_tenant_id          UUID NOT NULL REFERENCES control.tenant (id),
    notary_tenant_id        UUID NOT NULL REFERENCES control.tenant (id),
    firm_legal_case_id      UUID NOT NULL,
    firm_writing_file_id    UUID,
    expediente_codigo       VARCHAR(80) NOT NULL,
    asunto                  VARCHAR(500),
    estado                  VARCHAR(32) NOT NULL DEFAULT 'ENVIADO_A_NOTARIA'
        CHECK (estado IN (
            'ENVIADO_A_NOTARIA',
            'RECIBIDO',
            'EN_TRAMITE',
            'NOTARIA_COMPLETADA',
            'DEVUELTO_AL_BUFETE'
        )),
    paso_notarial           SMALLINT NOT NULL DEFAULT 0 CHECK (paso_notarial >= 0 AND paso_notarial <= 3),
    acta_label              VARCHAR(500),
    documentos_count        INTEGER NOT NULL DEFAULT 0 CHECK (documentos_count >= 0),
    emitido_por_user_id     UUID REFERENCES app.app_user (id),
    emitido_en              TIMESTAMPTZ NOT NULL DEFAULT now(),
    recibido_en             TIMESTAMPTZ,
    observaciones_bufete    TEXT,
    observaciones_notaria   TEXT,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    row_version             BIGINT NOT NULL DEFAULT 1,
    CHECK (firm_tenant_id <> notary_tenant_id)
);

CREATE INDEX ix_notary_remision_firm ON control.notary_remision (firm_tenant_id, estado, emitido_en DESC);
CREATE INDEX ix_notary_remision_notary ON control.notary_remision (notary_tenant_id, estado, emitido_en DESC);
CREATE INDEX ix_notary_remision_case ON control.notary_remision (firm_tenant_id, firm_legal_case_id);

COMMENT ON TABLE control.notary_remision IS
    'Paquete enviado bufete→notaría. firm_legal_case_id referencia app.legal_case del bufete (validación en API).';

CREATE TABLE control.notary_remision_document (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    remision_id                 UUID NOT NULL REFERENCES control.notary_remision (id) ON DELETE CASCADE,
    firm_tenant_id              UUID NOT NULL REFERENCES control.tenant (id),
    firm_document_version_id    UUID NOT NULL,
    display_name                VARCHAR(500) NOT NULL,
    sort_order                  INTEGER NOT NULL DEFAULT 0,
    size_bytes                  BIGINT,
    status_label                VARCHAR(40) NOT NULL DEFAULT 'INCLUIDO',
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_notary_remision_doc_remision ON control.notary_remision_document (remision_id, sort_order);

-- Permisos (globales; asignación a roles por tenant en inserts siguientes).
INSERT INTO app.permission (id, code, name, module_code) VALUES
    (gen_random_uuid(), 'escrituracion:notaria:asignar', 'Asignar notarías socias al bufete', 'escrituracion'),
    (gen_random_uuid(), 'escrituracion:notaria:enviar', 'Enviar expediente a notaría socia', 'escrituracion'),
    (gen_random_uuid(), 'notaria:remision:leer', 'Ver bandeja y detalle de remisiones', 'notaria'),
    (gen_random_uuid(), 'notaria:remision:actuar', 'Recibir y avanzar trámite notarial', 'notaria'),
    (gen_random_uuid(), 'notaria:partnership:gestionar', 'Gestionar vínculo bufete-notaría', 'notaria')
ON CONFLICT (code) DO NOTHING;

INSERT INTO app.role_permission (role_id, permission_id, tenant_id)
SELECT r.id, p.id, r.tenant_id
FROM app.role r
JOIN app.permission p ON p.code IN (
    'escrituracion:notaria:asignar',
    'escrituracion:notaria:enviar',
    'notaria:remision:leer',
    'notaria:remision:actuar',
    'notaria:partnership:gestionar'
)
WHERE r.code IN ('ADMIN', 'ABOGADO_SENIOR')
ON CONFLICT DO NOTHING;

INSERT INTO app.role_permission (role_id, permission_id, tenant_id)
SELECT r.id, p.id, r.tenant_id
FROM app.role r
JOIN app.permission p ON p.code IN ('notaria:remision:leer', 'notaria:remision:actuar')
WHERE r.code = 'ANALISTA'
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------
-- Semilla demo: tenant notaría Quito + partnership con tenant-demo bufete
-- ---------------------------------------------------------------------------
INSERT INTO control.tenant (
    id, code, name, status, timezone, isolation_mode, plan_code, max_users, max_cases, tenant_kind
)
VALUES (
    'b1000000-0000-7000-8000-000000000002',
    'notaria-demo-quito',
    'Notaría Demo Quito',
    'ACTIVE',
    'America/Guayaquil',
    'SHARED',
    'NOTARY_DEMO',
    20,
    500,
    'NOTARY'
)
ON CONFLICT (id) DO UPDATE SET
    tenant_kind = EXCLUDED.tenant_kind,
    name = EXCLUDED.name,
    timezone = EXCLUDED.timezone;

UPDATE control.tenant
SET tenant_kind = 'LEGAL_FIRM'
WHERE id = 'b1000000-0000-7000-8000-000000000001';

INSERT INTO control.tenant_module (tenant_id, module_code, enabled)
SELECT 'b1000000-0000-7000-8000-000000000002', m, TRUE
FROM unnest(ARRAY['NOTARIA', 'OPERACION_LEGAL']) AS m
ON CONFLICT (tenant_id, module_code) DO UPDATE SET enabled = EXCLUDED.enabled;

INSERT INTO control.notary_tenant_profile (
    tenant_id, ruc, canton, notary_number, official_name, notary_holder_name, contact_email
)
VALUES (
    'b1000000-0000-7000-8000-000000000002',
    '1790123456001',
    'Quito',
    '12',
    'Notaría Décima Segunda del cantón Quito',
    'Juan Carlos Ramírez P.',
    'operador.notaria12.quito@lexia.demo'
)
ON CONFLICT (tenant_id) DO UPDATE SET
    ruc = EXCLUDED.ruc,
    official_name = EXCLUDED.official_name,
    contact_email = EXCLUDED.contact_email;

INSERT INTO app.legal_entity (id, tenant_id, nit, legal_name, trade_name, entity_type, domicile, status)
VALUES (
    'c1000000-0000-7000-8000-000000000002',
    'b1000000-0000-7000-8000-000000000002',
    '1790123456001',
    'Notaría Décima Segunda del cantón Quito',
    'Notaría 12 Quito',
    'NOTARY',
    'Quito, Pichincha',
    'ACTIVE'
)
ON CONFLICT DO NOTHING;

INSERT INTO control.tenant_notary_partnership (
    id, firm_tenant_id, notary_tenant_id, status, initiated_by_tenant_id, notary_contact_email, approved_at
)
VALUES (
    '01000000-0000-7000-8000-000000000001',
    'b1000000-0000-7000-8000-000000000001',
    'b1000000-0000-7000-8000-000000000002',
    'ACTIVE',
    'b1000000-0000-7000-8000-000000000001',
    'operador.notaria12.quito@lexia.demo',
    now()
)
ON CONFLICT (firm_tenant_id, notary_tenant_id) DO UPDATE SET status = 'ACTIVE';

INSERT INTO app.notary_firm_assignment (
    id, tenant_id, notary_tenant_id, partnership_id, active
)
VALUES (
    'a1000000-0000-7000-8000-000000000001',
    'b1000000-0000-7000-8000-000000000001',
    'b1000000-0000-7000-8000-000000000002',
    '01000000-0000-7000-8000-000000000001',
    TRUE
)
ON CONFLICT (tenant_id, notary_tenant_id) DO UPDATE SET active = TRUE;

INSERT INTO app.role (id, tenant_id, code, name, is_system) VALUES
    ('f2000000-0000-7000-8000-000000000005', 'b1000000-0000-7000-8000-000000000002', 'ADMIN', 'Administrador notaría', TRUE),
    ('f2000000-0000-7000-8000-000000000006', 'b1000000-0000-7000-8000-000000000002', 'ANALISTA', 'Operador notarial', TRUE)
ON CONFLICT DO NOTHING;

INSERT INTO app.role_permission (role_id, permission_id, tenant_id)
SELECT 'f2000000-0000-7000-8000-000000000005', p.id, 'b1000000-0000-7000-8000-000000000002'
FROM app.permission p
ON CONFLICT DO NOTHING;

INSERT INTO app.role_permission (role_id, permission_id, tenant_id)
SELECT 'f2000000-0000-7000-8000-000000000006', p.id, 'b1000000-0000-7000-8000-000000000002'
FROM app.permission p
WHERE p.code IN ('notaria:remision:leer', 'notaria:remision:actuar', 'auditoria:evento:leer')
ON CONFLICT DO NOTHING;

-- Operador notaría: misma identidad global, segunda membresía en tenant notaría.
INSERT INTO app.membership (id, user_id, tenant_id, legal_entity_id, status)
VALUES (
    'e1000000-0000-7000-8000-000000000003',
    'd1000000-0000-7000-8000-000000000002',
    'b1000000-0000-7000-8000-000000000002',
    'c1000000-0000-7000-8000-000000000002',
    'ACTIVE'
)
ON CONFLICT DO NOTHING;

INSERT INTO app.membership_role (membership_id, role_id, tenant_id)
VALUES (
    'e1000000-0000-7000-8000-000000000003',
    'f2000000-0000-7000-8000-000000000005',
    'b1000000-0000-7000-8000-000000000002'
)
ON CONFLICT DO NOTHING;
