-- Super administrador de plataforma: permisos y tenant PLATFORM (organización LEXIA, no cliente).

INSERT INTO app.permission (id, code, name, module_code) VALUES
    ('f3000000-0000-7000-8000-000000000001', 'platform:tenant:leer', 'Listar tenants de plataforma', 'platform'),
    ('f3000000-0000-7000-8000-000000000002', 'platform:tenant:provision', 'Alta de tenant (bufete/notaría)', 'platform')
ON CONFLICT (code) DO NOTHING;

INSERT INTO control.tenant (
    id, code, name, status, timezone, isolation_mode, plan_code, max_users, max_cases, tenant_kind
)
VALUES (
    'b1000000-0000-7000-8000-000000000003',
    'lexia-platform',
    'LEXIA Plataforma',
    'ACTIVE',
    'America/Guayaquil',
    'SHARED',
    'PLATFORM',
    NULL,
    NULL,
    'PLATFORM'
)
ON CONFLICT (id) DO UPDATE SET
    tenant_kind = EXCLUDED.tenant_kind,
    name = EXCLUDED.name,
    plan_code = EXCLUDED.plan_code;

INSERT INTO app.legal_entity (id, tenant_id, legal_name, trade_name, entity_type, domicile, status)
VALUES (
    'c1000000-0000-7000-8000-000000000003',
    'b1000000-0000-7000-8000-000000000003',
    'LEXIA S.A.',
    'LEXIA',
    'COMPANY',
    'Ecuador',
    'ACTIVE'
)
ON CONFLICT DO NOTHING;

INSERT INTO app.role (id, tenant_id, code, name, is_system)
VALUES (
    'f2000000-0000-7000-8000-000000000007',
    'b1000000-0000-7000-8000-000000000003',
    'PLATFORM_ADMIN',
    'Administrador de plataforma',
    TRUE
)
ON CONFLICT DO NOTHING;

INSERT INTO app.role_permission (role_id, permission_id, tenant_id)
SELECT 'f2000000-0000-7000-8000-000000000007', p.id, 'b1000000-0000-7000-8000-000000000003'
FROM app.permission p
WHERE p.code IN (
    'platform:tenant:leer',
    'platform:tenant:provision',
    'admin:tenant:leer',
    'auditoria:evento:leer'
)
ON CONFLICT DO NOTHING;

-- admin.demo puede operar plataforma (cambiar organización tras login).
INSERT INTO app.membership (id, user_id, tenant_id, legal_entity_id, status)
VALUES (
    'e1000000-0000-7000-8000-000000000004',
    'd1000000-0000-7000-8000-000000000002',
    'b1000000-0000-7000-8000-000000000003',
    'c1000000-0000-7000-8000-000000000003',
    'ACTIVE'
)
ON CONFLICT DO NOTHING;

INSERT INTO app.membership_role (membership_id, role_id, tenant_id)
VALUES (
    'e1000000-0000-7000-8000-000000000004',
    'f2000000-0000-7000-8000-000000000007',
    'b1000000-0000-7000-8000-000000000003'
)
ON CONFLICT DO NOTHING;
