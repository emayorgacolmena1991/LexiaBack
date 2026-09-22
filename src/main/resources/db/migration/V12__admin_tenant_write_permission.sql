-- Permiso de edición de parámetros del tenant.

INSERT INTO app.permission (id, code, name, module_code) VALUES
    ('f1000000-0000-7000-8000-000000000012', 'admin:tenant:escribir', 'Editar parámetros del tenant', 'admin')
ON CONFLICT (code) DO NOTHING;

INSERT INTO app.role_permission (role_id, permission_id, tenant_id)
SELECT 'f2000000-0000-7000-8000-000000000001', id, 'b1000000-0000-7000-8000-000000000001'
FROM app.permission
WHERE code = 'admin:tenant:escribir'
ON CONFLICT DO NOTHING;

INSERT INTO app.role_permission (role_id, permission_id, tenant_id)
SELECT 'f2000000-0000-7000-8000-000000000002', id, 'b1000000-0000-7000-8000-000000000001'
FROM app.permission
WHERE code = 'admin:tenant:escribir'
ON CONFLICT DO NOTHING;
