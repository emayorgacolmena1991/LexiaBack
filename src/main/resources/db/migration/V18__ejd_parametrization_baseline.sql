-- Baseline de parametrización Escrituración (EJD): gates TO-BE, validaciones V1–V6, catálogos.

-- Gates G-001…G-005 (LEXIA-04)
DELETE FROM app.case_gate
WHERE gate_def_id IN (
    SELECT id FROM app.gate_def
    WHERE process_definition_id = 'f4000000-0000-7000-8000-000000000001'
);

DELETE FROM app.gate_def
WHERE process_definition_id = 'f4000000-0000-7000-8000-000000000001';

INSERT INTO app.gate_def (id, tenant_id, process_definition_id, code, question, sort_order) VALUES
    ('f5000000-0000-7000-8000-000000000001', 'b1000000-0000-7000-8000-000000000001', 'f4000000-0000-7000-8000-000000000001',
     'G-001', '¿La documentación requerida está disponible?', 1),
    ('f5000000-0000-7000-8000-000000000002', 'b1000000-0000-7000-8000-000000000001', 'f4000000-0000-7000-8000-000000000001',
     'G-002', '¿El estudio jurídico permite continuar?', 2),
    ('f5000000-0000-7000-8000-000000000003', 'b1000000-0000-7000-8000-000000000001', 'f4000000-0000-7000-8000-000000000001',
     'G-003', '¿La observación fue resuelta?', 3),
    ('f5000000-0000-7000-8000-000000000004', 'b1000000-0000-7000-8000-000000000001', 'f4000000-0000-7000-8000-000000000001',
     'G-004', '¿La notaría aprueba la documentación?', 4),
    ('f5000000-0000-7000-8000-000000000005', 'b1000000-0000-7000-8000-000000000001', 'f4000000-0000-7000-8000-000000000001',
     'G-005', '¿La jurisdicción dispone de canal digital registral?', 5);

UPDATE app.validation_def SET label = 'Presencia documental' WHERE process_definition_id = 'f4000000-0000-7000-8000-000000000001' AND code = 'V1';
UPDATE app.validation_def SET label = 'Integridad / legibilidad' WHERE process_definition_id = 'f4000000-0000-7000-8000-000000000001' AND code = 'V2';
UPDATE app.validation_def SET label = 'Vigencia' WHERE process_definition_id = 'f4000000-0000-7000-8000-000000000001' AND code = 'V3';
UPDATE app.validation_def SET label = 'Consistencia interna' WHERE process_definition_id = 'f4000000-0000-7000-8000-000000000001' AND code = 'V4';
UPDATE app.validation_def SET label = 'Consistencia cruzada' WHERE process_definition_id = 'f4000000-0000-7000-8000-000000000001' AND code = 'V5';
UPDATE app.validation_def SET label = 'Evaluación jurídica' WHERE process_definition_id = 'f4000000-0000-7000-8000-000000000001' AND code = 'V6';

INSERT INTO app.catalog (id, tenant_id, code, name) VALUES
    ('f3000000-0000-7000-8000-000000000003', 'b1000000-0000-7000-8000-000000000001', 'ESC_OPERATION_TYPE', 'Tipos de operación — Escrituración')
ON CONFLICT (tenant_id, code) DO NOTHING;

INSERT INTO app.catalog_item (tenant_id, catalog_id, code, label, sort_order) VALUES
    ('b1000000-0000-7000-8000-000000000001', 'f3000000-0000-7000-8000-000000000003', 'COMPRAVENTA', 'Compraventa', 1),
    ('b1000000-0000-7000-8000-000000000001', 'f3000000-0000-7000-8000-000000000003', 'HIPOTECA', 'Hipoteca', 2),
    ('b1000000-0000-7000-8000-000000000001', 'f3000000-0000-7000-8000-000000000003', 'FIDUCIA', 'Fiducia inmobiliaria', 3),
    ('b1000000-0000-7000-8000-000000000001', 'f3000000-0000-7000-8000-000000000003', 'DONACION', 'Donación', 4),
    ('b1000000-0000-7000-8000-000000000001', 'f3000000-0000-7000-8000-000000000003', 'ADJUDICACION', 'Adjudicación', 5)
ON CONFLICT (catalog_id, code) DO NOTHING;

INSERT INTO app.catalog_item (tenant_id, catalog_id, code, label, sort_order) VALUES
    ('b1000000-0000-7000-8000-000000000001', 'f3000000-0000-7000-8000-000000000002', 'AVALUO', 'Avalúo comercial', 3),
    ('b1000000-0000-7000-8000-000000000001', 'f3000000-0000-7000-8000-000000000002', 'ESCRITURA_ANT', 'Escritura anterior', 4),
    ('b1000000-0000-7000-8000-000000000001', 'f3000000-0000-7000-8000-000000000002', 'PODER', 'Poder / representación', 5),
    ('b1000000-0000-7000-8000-000000000001', 'f3000000-0000-7000-8000-000000000002', 'LIBERTAD_GRAV', 'Certificado libertad y tradición', 6)
ON CONFLICT (catalog_id, code) DO NOTHING;

INSERT INTO app.permission (id, code, name, module_code) VALUES
    ('f1000000-0000-7000-8000-000000000013', 'admin:proceso:leer', 'Ver definición de proceso', 'admin'),
    ('f1000000-0000-7000-8000-000000000014', 'admin:proceso:escribir', 'Editar definición de proceso', 'admin')
ON CONFLICT (code) DO NOTHING;

INSERT INTO app.role_permission (role_id, permission_id, tenant_id)
SELECT 'f2000000-0000-7000-8000-000000000001', id, 'b1000000-0000-7000-8000-000000000001'
FROM app.permission
WHERE code IN ('admin:proceso:leer', 'admin:proceso:escribir')
ON CONFLICT DO NOTHING;
