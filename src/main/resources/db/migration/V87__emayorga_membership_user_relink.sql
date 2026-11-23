-- Enlaza membresías seed al usuario real de emayorga@colmenas.ec (puede tener id distinto).

UPDATE app.membership m
SET user_id = u.id
FROM app.app_user u
WHERE u.email = 'emayorga@colmenas.ec'
  AND m.id IN (
    'e1000000-0000-7000-8000-000000000010',
    'e1000000-0000-7000-8000-000000000011',
    'e1000000-0000-7000-8000-000000000012'
  )
  AND m.user_id IS DISTINCT FROM u.id;

INSERT INTO app.membership (id, user_id, tenant_id, legal_entity_id, status)
SELECT v.mid, u.id, v.tid, v.leid, 'ACTIVE'
FROM app.app_user u
CROSS JOIN (
    VALUES
        ('e1000000-0000-7000-8000-000000000010'::uuid, 'b1000000-0000-7000-8000-000000000001'::uuid, 'c1000000-0000-7000-8000-000000000001'::uuid),
        ('e1000000-0000-7000-8000-000000000011'::uuid, 'b1000000-0000-7000-8000-000000000002'::uuid, 'c1000000-0000-7000-8000-000000000002'::uuid),
        ('e1000000-0000-7000-8000-000000000012'::uuid, 'b1000000-0000-7000-8000-000000000003'::uuid, 'c1000000-0000-7000-8000-000000000003'::uuid)
) AS v(mid, tid, leid)
WHERE u.email = 'emayorga@colmenas.ec'
ON CONFLICT (id) DO UPDATE SET
    user_id = EXCLUDED.user_id,
    status = EXCLUDED.status;

INSERT INTO app.membership_role (membership_id, role_id, tenant_id)
VALUES
    ('e1000000-0000-7000-8000-000000000010', 'f2000000-0000-7000-8000-000000000001', 'b1000000-0000-7000-8000-000000000001'),
    ('e1000000-0000-7000-8000-000000000011', 'f2000000-0000-7000-8000-000000000005', 'b1000000-0000-7000-8000-000000000002'),
    ('e1000000-0000-7000-8000-000000000012', 'f2000000-0000-7000-8000-000000000007', 'b1000000-0000-7000-8000-000000000003')
ON CONFLICT DO NOTHING;
