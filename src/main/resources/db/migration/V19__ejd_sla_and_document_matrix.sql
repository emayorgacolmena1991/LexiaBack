-- SLA por etapa EJD y matriz operación × documento requerido.

ALTER TABLE app.process_stage_def
    ADD COLUMN IF NOT EXISTS sla_hours INTEGER CHECK (sla_hours IS NULL OR sla_hours BETWEEN 1 AND 8760);

UPDATE app.process_stage_def SET sla_hours = 48 WHERE process_definition_id = 'f4000000-0000-7000-8000-000000000001' AND code = 'e1';
UPDATE app.process_stage_def SET sla_hours = 120 WHERE process_definition_id = 'f4000000-0000-7000-8000-000000000001' AND code = 'e2';
UPDATE app.process_stage_def SET sla_hours = 168 WHERE process_definition_id = 'f4000000-0000-7000-8000-000000000001' AND code = 'e3';
UPDATE app.process_stage_def SET sla_hours = 72 WHERE process_definition_id = 'f4000000-0000-7000-8000-000000000001' AND code = 'e4';
UPDATE app.process_stage_def SET sla_hours = 96 WHERE process_definition_id = 'f4000000-0000-7000-8000-000000000001' AND code = 'e5';
UPDATE app.process_stage_def SET sla_hours = 72 WHERE process_definition_id = 'f4000000-0000-7000-8000-000000000001' AND code = 'e6';
UPDATE app.process_stage_def SET sla_hours = 48 WHERE process_definition_id = 'f4000000-0000-7000-8000-000000000001' AND code = 'e7';
UPDATE app.process_stage_def SET sla_hours = 240 WHERE process_definition_id = 'f4000000-0000-7000-8000-000000000001' AND code = 'e8';

CREATE TABLE app.ejd_operation_document_req (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES control.tenant (id),
    operation_code      VARCHAR(64) NOT NULL,
    document_type_code  VARCHAR(64) NOT NULL,
    sort_order          INTEGER NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, operation_code, document_type_code)
);

INSERT INTO app.ejd_operation_document_req (tenant_id, operation_code, document_type_code, sort_order) VALUES
    ('b1000000-0000-7000-8000-000000000001', 'COMPRAVENTA', 'CERT_TRADICION', 1),
    ('b1000000-0000-7000-8000-000000000001', 'COMPRAVENTA', 'LIBERTAD_GRAV', 2),
    ('b1000000-0000-7000-8000-000000000001', 'COMPRAVENTA', 'AVALUO', 3),
    ('b1000000-0000-7000-8000-000000000001', 'HIPOTECA', 'CERT_TRADICION', 1),
    ('b1000000-0000-7000-8000-000000000001', 'HIPOTECA', 'LIBERTAD_GRAV', 2),
    ('b1000000-0000-7000-8000-000000000001', 'HIPOTECA', 'MINUTA', 3),
    ('b1000000-0000-7000-8000-000000000001', 'FIDUCIA', 'CERT_TRADICION', 1),
    ('b1000000-0000-7000-8000-000000000001', 'FIDUCIA', 'ESCRITURA_ANT', 2),
    ('b1000000-0000-7000-8000-000000000001', 'FIDUCIA', 'PODER', 3),
    ('b1000000-0000-7000-8000-000000000001', 'DONACION', 'CERT_TRADICION', 1),
    ('b1000000-0000-7000-8000-000000000001', 'DONACION', 'LIBERTAD_GRAV', 2),
    ('b1000000-0000-7000-8000-000000000001', 'ADJUDICACION', 'CERT_TRADICION', 1),
    ('b1000000-0000-7000-8000-000000000001', 'ADJUDICACION', 'LIBERTAD_GRAV', 2),
    ('b1000000-0000-7000-8000-000000000001', 'ADJUDICACION', 'ESCRITURA_ANT', 3);
