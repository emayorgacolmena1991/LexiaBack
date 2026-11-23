-- Super administrador de plataforma LEXIA (provisión y gobernanza de tenants).

INSERT INTO app.membership (id, user_id, tenant_id, legal_entity_id, status)
SELECT
    'e1000000-0000-7000-8000-000000000012',
    u.id,
    'b1000000-0000-7000-8000-000000000003',
    'c1000000-0000-7000-8000-000000000003',
    'ACTIVE'
FROM app.app_user u
WHERE u.email = 'emayorga@colmenas.ec'
ON CONFLICT DO NOTHING;

INSERT INTO app.membership_role (membership_id, role_id, tenant_id)
VALUES (
    'e1000000-0000-7000-8000-000000000012',
    'f2000000-0000-7000-8000-000000000007',
    'b1000000-0000-7000-8000-000000000003'
)
ON CONFLICT DO NOTHING;
