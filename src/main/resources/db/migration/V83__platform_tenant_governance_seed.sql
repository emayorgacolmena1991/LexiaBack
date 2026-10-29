-- Gobernanza de tenants desde plataforma + segundo bufete demo (Guayaquil).

INSERT INTO app.permission (id, code, name, module_code) VALUES
    ('f3000000-0000-7000-8000-000000000003', 'platform:tenant:escribir', 'Editar y suspender tenants', 'platform')
ON CONFLICT (code) DO NOTHING;

INSERT INTO app.role_permission (role_id, permission_id, tenant_id)
SELECT 'f2000000-0000-7000-8000-000000000007', p.id, 'b1000000-0000-7000-8000-000000000003'
FROM app.permission p
WHERE p.code = 'platform:tenant:escribir'
ON CONFLICT DO NOTHING;

-- Segundo bufete demo (Ecuador) para pruebas multi-bufete sin formulario de alta.
INSERT INTO control.tenant (
    id, code, name, status, timezone, isolation_mode, plan_code, max_users, max_cases, tenant_kind
)
VALUES (
    'b1000000-0000-7000-8000-000000000004',
    'bufete-guayaquil',
    'Bufete Guayaquil Demo',
    'ACTIVE',
    'America/Guayaquil',
    'SHARED',
    'ENTERPRISE_DEMO',
    50,
    1000,
    'LEGAL_FIRM'
)
ON CONFLICT (id) DO UPDATE SET
    code = EXCLUDED.code,
    name = EXCLUDED.name,
    tenant_kind = EXCLUDED.tenant_kind;

INSERT INTO control.tenant_module (tenant_id, module_code, enabled)
SELECT 'b1000000-0000-7000-8000-000000000004', m, TRUE
FROM unnest(ARRAY['OPERACION_LEGAL', 'NOTARIA', 'COMPLIANCE']) AS m
ON CONFLICT (tenant_id, module_code) DO UPDATE SET enabled = EXCLUDED.enabled;

INSERT INTO app.legal_entity (id, tenant_id, nit, legal_name, trade_name, entity_type, domicile, status)
VALUES (
    'c1000000-0000-7000-8000-000000000004',
    'b1000000-0000-7000-8000-000000000004',
    '0990123456001',
    'Estudio Jurídico Guayaquil Demo S.A.',
    'Bufete Gye Demo',
    'COMPANY',
    'Guayaquil, Guayas',
    'ACTIVE'
)
ON CONFLICT DO NOTHING;

INSERT INTO app.tenant_parameter (tenant_id, param_key, param_value) VALUES
    ('b1000000-0000-7000-8000-000000000004', 'timezone', 'America/Guayaquil'),
    ('b1000000-0000-7000-8000-000000000004', 'case_code_pattern', 'GYE-YYYY-###'),
    ('b1000000-0000-7000-8000-000000000004', 'sla_default_hours', '72'),
    ('b1000000-0000-7000-8000-000000000004', 'invite_ttl_hours', '72'),
    ('b1000000-0000-7000-8000-000000000004', 'mfa_required', 'false')
ON CONFLICT (tenant_id, param_key) DO UPDATE SET param_value = EXCLUDED.param_value;

INSERT INTO app.role (id, tenant_id, code, name, is_system)
VALUES (
    'f2000000-0000-7000-8000-000000000008',
    'b1000000-0000-7000-8000-000000000004',
    'ADMIN',
    'Administrador bufete',
    TRUE
)
ON CONFLICT DO NOTHING;

INSERT INTO app.role_permission (role_id, permission_id, tenant_id)
SELECT 'f2000000-0000-7000-8000-000000000008', p.id, 'b1000000-0000-7000-8000-000000000004'
FROM app.permission p
WHERE p.code NOT LIKE 'platform:%'
ON CONFLICT DO NOTHING;

INSERT INTO app.app_user (id, email, display_name, status)
VALUES (
    'd1000000-0000-7000-8000-000000000004',
    'bufete.gye@lexia.demo',
    'Admin Bufete Guayaquil',
    'ACTIVE'
)
ON CONFLICT DO NOTHING;

INSERT INTO app.user_credential (user_id, password_hash, algorithm, must_change)
VALUES (
    'd1000000-0000-7000-8000-000000000004',
    'argon2id$demo-not-a-real-hash',
    'argon2id',
    TRUE
)
ON CONFLICT (user_id) DO NOTHING;

INSERT INTO app.membership (id, user_id, tenant_id, legal_entity_id, status)
VALUES (
    'e1000000-0000-7000-8000-000000000005',
    'd1000000-0000-7000-8000-000000000004',
    'b1000000-0000-7000-8000-000000000004',
    'c1000000-0000-7000-8000-000000000004',
    'ACTIVE'
)
ON CONFLICT DO NOTHING;

INSERT INTO app.membership_role (membership_id, role_id, tenant_id)
VALUES (
    'e1000000-0000-7000-8000-000000000005',
    'f2000000-0000-7000-8000-000000000008',
    'b1000000-0000-7000-8000-000000000004'
)
ON CONFLICT DO NOTHING;

INSERT INTO control.tenant_notary_partnership (
    id, firm_tenant_id, notary_tenant_id, status, initiated_by_tenant_id, notary_contact_email, approved_at
)
VALUES (
    '01000000-0000-7000-8000-000000000002',
    'b1000000-0000-7000-8000-000000000004',
    'b1000000-0000-7000-8000-000000000002',
    'ACTIVE',
    'b1000000-0000-7000-8000-000000000004',
    'operador.notaria12.quito@lexia.demo',
    now()
)
ON CONFLICT (firm_tenant_id, notary_tenant_id) DO UPDATE SET status = 'ACTIVE';
