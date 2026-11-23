-- Cuenta operativa principal (ambiente productivo-like): admin bufete + admin notaría demo.

INSERT INTO app.app_user (id, email, display_name, status)
VALUES (
    'd1000000-0000-7000-8000-000000000010',
    'emayorga@colmenas.ec',
    'Eduardo Mayorga',
    'ACTIVE'
)
ON CONFLICT (email) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    status = EXCLUDED.status;

INSERT INTO app.user_credential (user_id, password_hash, algorithm, must_change)
SELECT u.id, 'argon2id$bootstrap-pending', 'argon2id', FALSE
FROM app.app_user u
WHERE u.email = 'emayorga@colmenas.ec'
ON CONFLICT (user_id) DO NOTHING;

INSERT INTO app.membership (id, user_id, tenant_id, legal_entity_id, status)
SELECT
    'e1000000-0000-7000-8000-000000000010',
    u.id,
    'b1000000-0000-7000-8000-000000000001',
    'c1000000-0000-7000-8000-000000000001',
    'ACTIVE'
FROM app.app_user u
WHERE u.email = 'emayorga@colmenas.ec'
ON CONFLICT DO NOTHING;

INSERT INTO app.membership_role (membership_id, role_id, tenant_id)
VALUES (
    'e1000000-0000-7000-8000-000000000010',
    'f2000000-0000-7000-8000-000000000001',
    'b1000000-0000-7000-8000-000000000001'
)
ON CONFLICT DO NOTHING;

INSERT INTO app.membership (id, user_id, tenant_id, legal_entity_id, status)
SELECT
    'e1000000-0000-7000-8000-000000000011',
    u.id,
    'b1000000-0000-7000-8000-000000000002',
    'c1000000-0000-7000-8000-000000000002',
    'ACTIVE'
FROM app.app_user u
WHERE u.email = 'emayorga@colmenas.ec'
ON CONFLICT DO NOTHING;

INSERT INTO app.membership_role (membership_id, role_id, tenant_id)
VALUES (
    'e1000000-0000-7000-8000-000000000011',
    'f2000000-0000-7000-8000-000000000005',
    'b1000000-0000-7000-8000-000000000002'
)
ON CONFLICT DO NOTHING;
