-- Tipo de operación en expediente, reglas EJD V1–V6 y enlace validation_def → rule_def.

ALTER TABLE app.legal_case
    ADD COLUMN IF NOT EXISTS operation_type_code VARCHAR(64);

ALTER TABLE app.validation_def
    ADD COLUMN IF NOT EXISTS rule_def_id UUID;

ALTER TABLE app.validation_def
    ADD CONSTRAINT validation_def_rule_def_fk
        FOREIGN KEY (rule_def_id, tenant_id) REFERENCES app.rule_def (id, tenant_id);

INSERT INTO app.rule_def (id, tenant_id, code, version, name, body, status) VALUES
    ('f5000000-0000-7000-8000-000000000001', 'b1000000-0000-7000-8000-000000000001', 'EJD.V1', 1, 'Presencia documental', '{"engine":"document_presence"}', 'ACTIVE'),
    ('f5000000-0000-7000-8000-000000000002', 'b1000000-0000-7000-8000-000000000001', 'EJD.V2', 1, 'Integridad / legibilidad', '{"engine":"manual"}', 'ACTIVE'),
    ('f5000000-0000-7000-8000-000000000003', 'b1000000-0000-7000-8000-000000000001', 'EJD.V3', 1, 'Vigencia', '{"engine":"manual"}', 'ACTIVE'),
    ('f5000000-0000-7000-8000-000000000004', 'b1000000-0000-7000-8000-000000000001', 'EJD.V4', 1, 'Consistencia interna', '{"engine":"manual"}', 'ACTIVE'),
    ('f5000000-0000-7000-8000-000000000005', 'b1000000-0000-7000-8000-000000000001', 'EJD.V5', 1, 'Consistencia cruzada', '{"engine":"manual"}', 'ACTIVE'),
    ('f5000000-0000-7000-8000-000000000006', 'b1000000-0000-7000-8000-000000000001', 'EJD.V6', 1, 'Evaluación jurídica', '{"engine":"manual"}', 'ACTIVE')
ON CONFLICT (tenant_id, code, version) DO NOTHING;

UPDATE app.validation_def v
SET rule_def_id = r.id
FROM app.rule_def r
WHERE v.tenant_id = r.tenant_id
  AND v.process_definition_id = 'f4000000-0000-7000-8000-000000000001'
  AND v.code = 'V1' AND r.code = 'EJD.V1';

UPDATE app.validation_def v
SET rule_def_id = r.id
FROM app.rule_def r
WHERE v.tenant_id = r.tenant_id
  AND v.process_definition_id = 'f4000000-0000-7000-8000-000000000001'
  AND v.code = 'V2' AND r.code = 'EJD.V2';

UPDATE app.validation_def v
SET rule_def_id = r.id
FROM app.rule_def r
WHERE v.tenant_id = r.tenant_id
  AND v.process_definition_id = 'f4000000-0000-7000-8000-000000000001'
  AND v.code = 'V3' AND r.code = 'EJD.V3';

UPDATE app.validation_def v
SET rule_def_id = r.id
FROM app.rule_def r
WHERE v.tenant_id = r.tenant_id
  AND v.process_definition_id = 'f4000000-0000-7000-8000-000000000001'
  AND v.code = 'V4' AND r.code = 'EJD.V4';

UPDATE app.validation_def v
SET rule_def_id = r.id
FROM app.rule_def r
WHERE v.tenant_id = r.tenant_id
  AND v.process_definition_id = 'f4000000-0000-7000-8000-000000000001'
  AND v.code = 'V5' AND r.code = 'EJD.V5';

UPDATE app.validation_def v
SET rule_def_id = r.id
FROM app.rule_def r
WHERE v.tenant_id = r.tenant_id
  AND v.process_definition_id = 'f4000000-0000-7000-8000-000000000001'
  AND v.code = 'V6' AND r.code = 'EJD.V6';
