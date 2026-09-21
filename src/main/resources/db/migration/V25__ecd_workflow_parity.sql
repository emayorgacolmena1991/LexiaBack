-- Paridad ECD: validaciones, reglas, transiciones C1→C9, gates por etapa e integraciones.

INSERT INTO app.validation_def (tenant_id, process_definition_id, code, label, sort_order)
SELECT 'b1000000-0000-7000-8000-000000000001', 'f4000000-0000-7000-8000-000000000002', v.code, v.label, v.sort_order
FROM (VALUES
    ('V1', 'Título ejecutivo presente', 1),
    ('V2', 'Integridad documental', 2),
    ('V3', 'Vigencia de soportes', 3),
    ('V4', 'Consistencia de obligación', 4)
) AS v(code, label, sort_order)
WHERE NOT EXISTS (
    SELECT 1 FROM app.validation_def d
    WHERE d.process_definition_id = 'f4000000-0000-7000-8000-000000000002' AND d.code = v.code
);

INSERT INTO app.rule_def (id, tenant_id, code, version, name, body, status) VALUES
    ('f5000000-0000-7000-8000-000000000101', 'b1000000-0000-7000-8000-000000000001', 'ECD.V1', 1, 'Título ejecutivo', '{"engine":"document_presence","domain":"ecd"}', 'ACTIVE'),
    ('f5000000-0000-7000-8000-000000000102', 'b1000000-0000-7000-8000-000000000001', 'ECD.V2', 1, 'Integridad documental', '{"engine":"document_integrity"}', 'ACTIVE'),
    ('f5000000-0000-7000-8000-000000000103', 'b1000000-0000-7000-8000-000000000001', 'ECD.V3', 1, 'Vigencia', '{"engine":"document_vigency"}', 'ACTIVE'),
    ('f5000000-0000-7000-8000-000000000104', 'b1000000-0000-7000-8000-000000000001', 'ECD.V4', 1, 'Consistencia interna', '{"engine":"internal_consistency"}', 'ACTIVE')
ON CONFLICT (tenant_id, code, version) DO NOTHING;

UPDATE app.validation_def v
SET rule_def_id = r.id
FROM app.rule_def r
WHERE v.tenant_id = r.tenant_id
  AND v.process_definition_id = 'f4000000-0000-7000-8000-000000000002'
  AND v.code = 'V1' AND r.code = 'ECD.V1';

UPDATE app.validation_def v
SET rule_def_id = r.id
FROM app.rule_def r
WHERE v.tenant_id = r.tenant_id
  AND v.process_definition_id = 'f4000000-0000-7000-8000-000000000002'
  AND v.code = 'V2' AND r.code = 'ECD.V2';

UPDATE app.validation_def v
SET rule_def_id = r.id
FROM app.rule_def r
WHERE v.tenant_id = r.tenant_id
  AND v.process_definition_id = 'f4000000-0000-7000-8000-000000000002'
  AND v.code = 'V3' AND r.code = 'ECD.V3';

UPDATE app.validation_def v
SET rule_def_id = r.id
FROM app.rule_def r
WHERE v.tenant_id = r.tenant_id
  AND v.process_definition_id = 'f4000000-0000-7000-8000-000000000002'
  AND v.code = 'V4' AND r.code = 'ECD.V4';

INSERT INTO app.process_stage_transition (tenant_id, process_definition_id, from_stage_code, to_stage_code) VALUES
    ('b1000000-0000-7000-8000-000000000001', 'f4000000-0000-7000-8000-000000000002', 'c1', 'c2'),
    ('b1000000-0000-7000-8000-000000000001', 'f4000000-0000-7000-8000-000000000002', 'c2', 'c3'),
    ('b1000000-0000-7000-8000-000000000001', 'f4000000-0000-7000-8000-000000000002', 'c3', 'c4'),
    ('b1000000-0000-7000-8000-000000000001', 'f4000000-0000-7000-8000-000000000002', 'c4', 'c5'),
    ('b1000000-0000-7000-8000-000000000001', 'f4000000-0000-7000-8000-000000000002', 'c5', 'c6'),
    ('b1000000-0000-7000-8000-000000000001', 'f4000000-0000-7000-8000-000000000002', 'c6', 'c7'),
    ('b1000000-0000-7000-8000-000000000001', 'f4000000-0000-7000-8000-000000000002', 'c7', 'c8'),
    ('b1000000-0000-7000-8000-000000000001', 'f4000000-0000-7000-8000-000000000002', 'c8', 'c9')
ON CONFLICT (tenant_id, process_definition_id, from_stage_code, to_stage_code) DO NOTHING;

INSERT INTO app.ejd_stage_gate_req (tenant_id, stage_code, gate_code, sort_order) VALUES
    ('b1000000-0000-7000-8000-000000000001', 'c3', 'CO-G-001', 1),
    ('b1000000-0000-7000-8000-000000000001', 'c4', 'CO-G-001', 1),
    ('b1000000-0000-7000-8000-000000000001', 'c4', 'CO-G-002', 2)
ON CONFLICT (tenant_id, stage_code, gate_code) DO NOTHING;

INSERT INTO app.ejd_stage_integration (tenant_id, stage_code, integration_code, sort_order) VALUES
    ('b1000000-0000-7000-8000-000000000001', 'c6', 'QUIPUX', 1),
    ('b1000000-0000-7000-8000-000000000001', 'c7', 'REGISTRO', 1)
ON CONFLICT (tenant_id, stage_code, integration_code) DO NOTHING;

INSERT INTO app.catalog_item (tenant_id, catalog_id, code, label, sort_order)
SELECT 'b1000000-0000-7000-8000-000000000001', 'f3000000-0000-7000-8000-000000000002', 'TITULO_EJECUTIVO', 'Título ejecutivo', 3
WHERE NOT EXISTS (
    SELECT 1 FROM app.catalog_item i
    WHERE i.catalog_id = 'f3000000-0000-7000-8000-000000000002' AND i.code = 'TITULO_EJECUTIVO'
);
