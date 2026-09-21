-- Reglas ECD.V1–V4 y enlace a validation_def por tenant (paridad admin + workspace).

INSERT INTO app.rule_def (id, tenant_id, code, version, name, body, status)
SELECT gen_random_uuid(), t.id, r.code, 1, r.name, r.body, 'ACTIVE'
FROM control.tenant t
CROSS JOIN (
    VALUES
        ('ECD.V1', 'Título ejecutivo', '{"engine":"document_presence","domain":"ecd"}'),
        ('ECD.V2', 'Integridad documental', '{"engine":"document_integrity"}'),
        ('ECD.V3', 'Vigencia', '{"engine":"document_vigency"}'),
        ('ECD.V4', 'Consistencia interna', '{"engine":"internal_consistency"}')
) AS r(code, name, body)
WHERE t.deleted_at IS NULL
  AND NOT EXISTS (
    SELECT 1
    FROM app.rule_def rd
    WHERE rd.tenant_id = t.id
      AND rd.code = r.code
      AND rd.version = 1
  );

UPDATE app.validation_def v
SET rule_def_id = r.id
FROM app.rule_def r, app.process_definition pd
WHERE v.process_definition_id = pd.id
  AND v.tenant_id = r.tenant_id
  AND pd.case_type = 'ECD'
  AND pd.deleted_at IS NULL
  AND v.rule_def_id IS NULL
  AND r.code = 'ECD.' || v.code
  AND r.version = 1;
