-- El rol ABOGADO_SENIOR puede invitar usuarios al tenant (demo y operación habitual).

INSERT INTO app.role_permission (role_id, permission_id, tenant_id)
SELECT
    'f2000000-0000-7000-8000-000000000002',
    'f1000000-0000-7000-8000-000000000002',
    'b1000000-0000-7000-8000-000000000001'
WHERE NOT EXISTS (
    SELECT 1
    FROM app.role_permission
    WHERE role_id = 'f2000000-0000-7000-8000-000000000002'
      AND permission_id = 'f1000000-0000-7000-8000-000000000002'
      AND tenant_id = 'b1000000-0000-7000-8000-000000000001'
);
