-- Usuario tenant ADMIN para pruebas E2E de parametrización (no credencial productiva).

INSERT INTO app.app_user (id, email, display_name, status)
VALUES (
    'd1000000-0000-7000-8000-000000000002',
    'admin.demo@lexia.demo',
    'Admin Demo',
    'ACTIVE'
)
ON CONFLICT DO NOTHING;

INSERT INTO app.user_credential (user_id, password_hash, algorithm, must_change)
VALUES (
    'd1000000-0000-7000-8000-000000000002',
    'argon2id$demo-not-a-real-hash',
    'argon2id',
    TRUE
)
ON CONFLICT (user_id) DO NOTHING;

INSERT INTO app.membership (id, user_id, tenant_id, legal_entity_id, status)
VALUES (
    'e1000000-0000-7000-8000-000000000002',
    'd1000000-0000-7000-8000-000000000002',
    'b1000000-0000-7000-8000-000000000001',
    'c1000000-0000-7000-8000-000000000001',
    'ACTIVE'
)
ON CONFLICT DO NOTHING;

INSERT INTO app.membership_role (membership_id, role_id, tenant_id)
VALUES (
    'e1000000-0000-7000-8000-000000000002',
    'f2000000-0000-7000-8000-000000000001',
    'b1000000-0000-7000-8000-000000000001'
)
ON CONFLICT DO NOTHING;
