-- Tras Flyway: usuario demo con rol ADMIN para cubrir todas las secciones en E2E.

INSERT INTO app.membership_role (membership_id, role_id, tenant_id)
VALUES (
    'e1000000-0000-7000-8000-000000000001',
    'f2000000-0000-7000-8000-000000000001',
    'b1000000-0000-7000-8000-000000000001'
)
ON CONFLICT (membership_id, role_id) DO NOTHING;
