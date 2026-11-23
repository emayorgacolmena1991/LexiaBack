-- Identificadores de producto para el tenant notaría seed (UUID V79).
-- Idempotente: aplica solo si aún existe el código legacy.

UPDATE control.tenant
SET
    code = 'notaria-12-quito',
    name = 'Notaría Décima Segunda · Quito',
    plan_code = 'NOTARY'
WHERE id = 'b1000000-0000-7000-8000-000000000002'
  AND code IN ('notaria-demo-quito', 'notaria-12-quito');

COMMENT ON COLUMN control.tenant.code IS
    'Código único de tenant (URL/API). Ej.: notaria-12-quito, tenant-demo.';
