-- Ver toda la bandeja es un permiso distinto de leer un expediente.
-- ADMIN y ABOGADO_SENIOR lo reciben. El resto solo ve los expedientes asignados
-- hasta que un administrador marque este permiso en su rol.

INSERT INTO app.permission (id, code, name, module_code) VALUES
    ('f1000000-0000-7000-8000-000000000108', 'expedientes:caso:leer_todos', 'Ver todos los expedientes', 'expedientes')
ON CONFLICT (code) DO NOTHING;

INSERT INTO app.role_permission (role_id, permission_id, tenant_id)
SELECT r.id, p.id, r.tenant_id
FROM app.role r
JOIN app.permission p ON p.code = 'expedientes:caso:leer_todos'
WHERE r.code IN ('ADMIN', 'ABOGADO_SENIOR')
ON CONFLICT DO NOTHING;