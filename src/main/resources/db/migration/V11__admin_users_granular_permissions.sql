-- Permisos granulares de administración de usuarios.

INSERT INTO app.permission (id, code, name, module_code) VALUES
    ('f1000000-0000-7000-8000-00000000000f', 'admin:usuarios:leer', 'Ver usuarios e invitaciones', 'admin'),
    ('f1000000-0000-7000-8000-000000000010', 'admin:usuarios:editar', 'Editar acceso de usuarios', 'admin'),
    ('f1000000-0000-7000-8000-000000000011', 'admin:usuarios:revocar', 'Revocar invitaciones', 'admin')
ON CONFLICT (code) DO NOTHING;

INSERT INTO app.role_permission (role_id, permission_id, tenant_id)
SELECT 'f2000000-0000-7000-8000-000000000001', id, 'b1000000-0000-7000-8000-000000000001'
FROM app.permission
WHERE code IN (
    'admin:usuarios:leer',
    'admin:usuarios:editar',
    'admin:usuarios:revocar'
)
ON CONFLICT DO NOTHING;

INSERT INTO app.role_permission (role_id, permission_id, tenant_id)
SELECT 'f2000000-0000-7000-8000-000000000002', id, 'b1000000-0000-7000-8000-000000000001'
FROM app.permission
WHERE code IN (
    'admin:usuarios:leer',
    'admin:usuarios:editar',
    'admin:usuarios:revocar'
)
ON CONFLICT DO NOTHING;
