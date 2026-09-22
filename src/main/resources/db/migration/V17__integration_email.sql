-- Canal de correo transaccional como integración administrable.

ALTER TABLE app.integration DROP CONSTRAINT IF EXISTS integration_code_check;

ALTER TABLE app.integration
    ADD CONSTRAINT integration_code_check
    CHECK (code IN ('QUIPUX', 'NOTARIA', 'MUNICIPIO', 'REGISTRO', 'FIRMA', 'PAGOS', 'EMAIL'));

INSERT INTO app.integration (tenant_id, code, name, enabled)
VALUES (
    'b1000000-0000-7000-8000-000000000001',
    'EMAIL',
    'Correo transaccional (Brevo)',
    FALSE
)
ON CONFLICT (tenant_id, code) DO NOTHING;
