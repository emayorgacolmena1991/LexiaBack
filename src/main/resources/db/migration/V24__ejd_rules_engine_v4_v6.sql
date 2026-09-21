-- LEXIA-10: motores determinísticos V2–V6 (V1 ya en código).

UPDATE app.rule_def
SET body = '{"engine":"document_integrity"}'
WHERE tenant_id = 'b1000000-0000-7000-8000-000000000001'
  AND code = 'EJD.V2'
  AND version = 1;

UPDATE app.rule_def
SET body = '{"engine":"document_vigency"}'
WHERE tenant_id = 'b1000000-0000-7000-8000-000000000001'
  AND code = 'EJD.V3'
  AND version = 1;

UPDATE app.rule_def
SET body = '{"engine":"internal_consistency"}'
WHERE tenant_id = 'b1000000-0000-7000-8000-000000000001'
  AND code = 'EJD.V4'
  AND version = 1;

UPDATE app.rule_def
SET body = '{"engine":"cross_document","keys":["MATRICULA","FOLIO"]}'
WHERE tenant_id = 'b1000000-0000-7000-8000-000000000001'
  AND code = 'EJD.V5'
  AND version = 1;

UPDATE app.rule_def
SET body = '{"engine":"human_legal_review"}'
WHERE tenant_id = 'b1000000-0000-7000-8000-000000000001'
  AND code = 'EJD.V6'
  AND version = 1;
