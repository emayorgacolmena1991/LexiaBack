-- Purga datos operativos de Escrituración (EJD) y Coactivas.
-- Ejecutar como lexia_migrator (dueño de las tablas; si no, RLS deja el DELETE en 0).
-- No toca usuarios, tenants, roles, catálogos, prompts, delegados ni plantillas.
-- Conserva casos ECD y app.collection_*.

BEGIN;

DELETE FROM app.jobs;
DELETE FROM app.sse_evento;
DELETE FROM app.ia_log_consumo;
DELETE FROM app.documento_cache;
DELETE FROM app.documento_texto_ocr;

UPDATE app.coactiva_analisis SET analisis_padre_id = NULL;
UPDATE app.coactiva_archivo SET acta_id = NULL, expediente_id = NULL;
UPDATE app.coactiva_acta SET archivo_id = NULL;
UPDATE app.coactiva_expediente SET acta_entrega_id = NULL;

DELETE FROM app.coactiva_tarea_sugerida;
DELETE FROM app.coactiva_analisis;
DELETE FROM app.coactiva_documento_draft;
DELETE FROM app.coactiva_embargo_registro;
DELETE FROM app.coactiva_embargo_lote;
DELETE FROM app.coactiva_notificacion;
DELETE FROM app.coactiva_evento;
DELETE FROM app.coactiva_actuacion;
DELETE FROM app.coactiva_medida;
DELETE FROM app.coactiva_solicitud;
DELETE FROM app.coactiva_etapa_historial;
DELETE FROM app.coactiva_participante;
DELETE FROM app.coactiva_acta_item;
DELETE FROM app.coactiva_archivo;
DELETE FROM app.coactiva_acta;
DELETE FROM app.coactiva_expediente;

DELETE FROM app.ingesta_item;
DELETE FROM app.ingesta_lote;
DELETE FROM app.expediente_borrador;

UPDATE app.legal_case SET current_stage_id = NULL WHERE case_type = 'EJD';

DELETE FROM app.data_provenance
WHERE extracted_data_id IN (
    SELECT e.id FROM app.extracted_data e
    JOIN app.legal_case c ON c.id = e.case_id AND c.tenant_id = e.tenant_id
    WHERE c.case_type = 'EJD'
);

DELETE FROM app.evidence
WHERE case_id IN (SELECT id FROM app.legal_case WHERE case_type = 'EJD');

DELETE FROM app.title_observation
WHERE title_study_id IN (
    SELECT ts.id FROM app.title_study ts
    JOIN app.writing_file wf ON wf.id = ts.writing_file_id AND wf.tenant_id = ts.tenant_id
    JOIN app.legal_case c ON c.id = wf.case_id AND c.tenant_id = wf.tenant_id
    WHERE c.case_type = 'EJD'
);

DELETE FROM app.title_study
WHERE writing_file_id IN (
    SELECT wf.id FROM app.writing_file wf
    JOIN app.legal_case c ON c.id = wf.case_id AND c.tenant_id = wf.tenant_id
    WHERE c.case_type = 'EJD'
);

DELETE FROM app.minuta_draft
WHERE writing_file_id IN (
    SELECT wf.id FROM app.writing_file wf
    JOIN app.legal_case c ON c.id = wf.case_id AND c.tenant_id = wf.tenant_id
    WHERE c.case_type = 'EJD'
);

DELETE FROM app.notary_act
WHERE writing_file_id IN (
    SELECT wf.id FROM app.writing_file wf
    JOIN app.legal_case c ON c.id = wf.case_id AND c.tenant_id = wf.tenant_id
    WHERE c.case_type = 'EJD'
);

DELETE FROM app.municipal_tax
WHERE writing_file_id IN (
    SELECT wf.id FROM app.writing_file wf
    JOIN app.legal_case c ON c.id = wf.case_id AND c.tenant_id = wf.tenant_id
    WHERE c.case_type = 'EJD'
);

DELETE FROM app.registry_inscription
WHERE writing_file_id IN (
    SELECT wf.id FROM app.writing_file wf
    JOIN app.legal_case c ON c.id = wf.case_id AND c.tenant_id = wf.tenant_id
    WHERE c.case_type = 'EJD'
);

DELETE FROM app.processing_job
WHERE document_version_id IN (
    SELECT dv.id FROM app.document_version dv
    JOIN app.document d ON d.id = dv.document_id AND d.tenant_id = dv.tenant_id
    JOIN app.legal_case c ON c.id = d.case_id AND c.tenant_id = d.tenant_id
    WHERE c.case_type = 'EJD'
);

DELETE FROM app.document_classification
WHERE document_id IN (
    SELECT d.id FROM app.document d
    JOIN app.legal_case c ON c.id = d.case_id AND c.tenant_id = d.tenant_id
    WHERE c.case_type = 'EJD'
);

DELETE FROM app.extracted_data
WHERE case_id IN (SELECT id FROM app.legal_case WHERE case_type = 'EJD');

DELETE FROM app.document_version
WHERE document_id IN (
    SELECT d.id FROM app.document d
    JOIN app.legal_case c ON c.id = d.case_id AND c.tenant_id = d.tenant_id
    WHERE c.case_type = 'EJD'
);

DELETE FROM app.writing_file
WHERE case_id IN (SELECT id FROM app.legal_case WHERE case_type = 'EJD');

DELETE FROM app.document
WHERE case_id IN (SELECT id FROM app.legal_case WHERE case_type = 'EJD');

DELETE FROM app.case_party WHERE case_id IN (SELECT id FROM app.legal_case WHERE case_type = 'EJD');
DELETE FROM app.case_assignment WHERE case_id IN (SELECT id FROM app.legal_case WHERE case_type = 'EJD');
DELETE FROM app.case_note WHERE case_id IN (SELECT id FROM app.legal_case WHERE case_type = 'EJD');
DELETE FROM app.case_gate WHERE case_id IN (SELECT id FROM app.legal_case WHERE case_type = 'EJD');
DELETE FROM app.case_validation WHERE case_id IN (SELECT id FROM app.legal_case WHERE case_type = 'EJD');
DELETE FROM app.legal_review WHERE case_id IN (SELECT id FROM app.legal_case WHERE case_type = 'EJD');
DELETE FROM app.human_decision WHERE case_id IN (SELECT id FROM app.legal_case WHERE case_type = 'EJD');
DELETE FROM app.case_task WHERE case_id IN (SELECT id FROM app.legal_case WHERE case_type = 'EJD');
DELETE FROM app.case_exception WHERE case_id IN (SELECT id FROM app.legal_case WHERE case_type = 'EJD');
DELETE FROM app.case_action WHERE case_id IN (SELECT id FROM app.legal_case WHERE case_type = 'EJD');
DELETE FROM app.sla_clock WHERE case_id IN (SELECT id FROM app.legal_case WHERE case_type = 'EJD');
DELETE FROM app.case_acl WHERE case_id IN (SELECT id FROM app.legal_case WHERE case_type = 'EJD');
DELETE FROM app.workflow_instance WHERE case_id IN (SELECT id FROM app.legal_case WHERE case_type = 'EJD');
DELETE FROM app.case_stage WHERE case_id IN (SELECT id FROM app.legal_case WHERE case_type = 'EJD');

DELETE FROM app.legal_case WHERE case_type = 'EJD';

COMMIT;
