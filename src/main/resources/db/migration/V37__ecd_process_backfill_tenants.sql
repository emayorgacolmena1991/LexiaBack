-- Asegura definición ECD (C1–C9) en tenants que aún no tienen etapas parametrizadas.

INSERT INTO app.process_definition (id, tenant_id, code, name, case_type)
SELECT gen_random_uuid(), t.id, 'ECD', 'Coactivas C1–C9', 'ECD'
FROM control.tenant t
WHERE t.deleted_at IS NULL
  AND NOT EXISTS (
    SELECT 1
    FROM app.process_definition pd
    WHERE pd.tenant_id = t.id
      AND pd.case_type = 'ECD'
      AND pd.deleted_at IS NULL
  );

INSERT INTO app.process_stage_def (tenant_id, process_definition_id, code, label, short_label, sort_order)
SELECT pd.tenant_id, pd.id, v.code, v.label, v.short_label, v.sort_order
FROM app.process_definition pd
CROSS JOIN (
    VALUES
        ('c1', 'Apertura', 'Apertura', 1),
        ('c2', 'Digitalización', 'Digitalización', 2),
        ('c3', 'Validación', 'Validación', 3),
        ('c4', 'Análisis jurídico', 'Análisis', 4),
        ('c5', 'Procedencia', 'Procedencia', 5),
        ('c6', 'Mandamiento', 'Mandamiento', 6),
        ('c7', 'Ejecución / medidas', 'Medidas', 7),
        ('c8', 'Seguimiento', 'Seguimiento', 8),
        ('c9', 'Cierre', 'Cierre', 9)
) AS v(code, label, short_label, sort_order)
WHERE pd.case_type = 'ECD'
  AND pd.deleted_at IS NULL
  AND NOT EXISTS (
    SELECT 1
    FROM app.process_stage_def s
    WHERE s.process_definition_id = pd.id
      AND s.tenant_id = pd.tenant_id
  );

INSERT INTO app.gate_def (tenant_id, process_definition_id, code, question, sort_order)
SELECT pd.tenant_id, pd.id, v.code, v.question, v.sort_order
FROM app.process_definition pd
CROSS JOIN (
    VALUES
        ('CO-G-001', '¿Título ejecutivo presente en el expediente?', 1),
        ('CO-G-002', '¿La acción coactiva procede a revisión humana?', 2)
) AS v(code, question, sort_order)
WHERE pd.case_type = 'ECD'
  AND pd.deleted_at IS NULL
  AND NOT EXISTS (
    SELECT 1
    FROM app.gate_def g
    WHERE g.process_definition_id = pd.id
      AND g.tenant_id = pd.tenant_id
  );

INSERT INTO app.validation_def (tenant_id, process_definition_id, code, label, sort_order)
SELECT pd.tenant_id, pd.id, v.code, v.label, v.sort_order
FROM app.process_definition pd
CROSS JOIN (
    VALUES
        ('V1', 'Título ejecutivo presente', 1),
        ('V2', 'Integridad documental', 2),
        ('V3', 'Vigencia de soportes', 3),
        ('V4', 'Consistencia de obligación', 4)
) AS v(code, label, sort_order)
WHERE pd.case_type = 'ECD'
  AND pd.deleted_at IS NULL
  AND NOT EXISTS (
    SELECT 1
    FROM app.validation_def val
    WHERE val.process_definition_id = pd.id
      AND val.tenant_id = pd.tenant_id
  );

INSERT INTO app.process_stage_transition (tenant_id, process_definition_id, from_stage_code, to_stage_code)
SELECT pd.tenant_id, pd.id, v.from_code, v.to_code
FROM app.process_definition pd
CROSS JOIN (
    VALUES
        ('c1', 'c2'),
        ('c2', 'c3'),
        ('c3', 'c4'),
        ('c4', 'c5'),
        ('c5', 'c6'),
        ('c6', 'c7'),
        ('c7', 'c8'),
        ('c8', 'c9')
) AS v(from_code, to_code)
WHERE pd.case_type = 'ECD'
  AND pd.deleted_at IS NULL
  AND NOT EXISTS (
    SELECT 1
    FROM app.process_stage_transition t
    WHERE t.process_definition_id = pd.id
      AND t.tenant_id = pd.tenant_id
  );
