-- Escrituración BIESS — flujo abogado (validación + minutas).
-- NO borra matrices legacy: las marca DEPRECATED.
-- Etapas e5–e8 (notaría/municipio/registro) quedan DEPRECATED para activar después.

-- ---------------------------------------------------------------------------
-- 1. Deprecar matrices conflictivas (conservar filas)
-- ---------------------------------------------------------------------------
ALTER TABLE app.act_requirement
    ADD COLUMN IF NOT EXISTS deprecated_at TIMESTAMPTZ;

ALTER TABLE app.ejd_operation_document_req
    ADD COLUMN IF NOT EXISTS deprecated_at TIMESTAMPTZ;

UPDATE app.act_requirement
SET deprecated_at = now()
WHERE deprecated_at IS NULL;

UPDATE app.ejd_operation_document_req
SET deprecated_at = now()
WHERE deprecated_at IS NULL;

COMMENT ON COLUMN app.act_requirement.deprecated_at IS
    'DEPRECATED: usar app.document_requirement por producto BIESS. No borrar filas.';
COMMENT ON COLUMN app.ejd_operation_document_req.deprecated_at IS
    'DEPRECATED: usar app.document_requirement por producto BIESS. No borrar filas.';

-- ---------------------------------------------------------------------------
-- 2. Etapas: active + owner_actor + deprecated_at
-- ---------------------------------------------------------------------------
ALTER TABLE app.process_stage_def
    ADD COLUMN IF NOT EXISTS active BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE app.process_stage_def
    ADD COLUMN IF NOT EXISTS owner_actor VARCHAR(32) NOT NULL DEFAULT 'ABOGADO';

ALTER TABLE app.process_stage_def
    ADD COLUMN IF NOT EXISTS deprecated_at TIMESTAMPTZ;

-- Relabel flujo abogado (e1–e4)
UPDATE app.process_stage_def SET
    label = 'Recepción e ingesta / producto',
    short_label = 'Recepción',
    owner_actor = 'ABOGADO',
    active = TRUE,
    sla_hours = COALESCE(sla_hours, 48)
WHERE process_definition_id = 'f4000000-0000-7000-8000-000000000001' AND code = 'e1';

UPDATE app.process_stage_def SET
    label = 'Estudio de título (OCR / IA)',
    short_label = 'Estudio',
    owner_actor = 'ABOGADO',
    active = TRUE,
    sla_hours = COALESCE(sla_hours, 48)
WHERE process_definition_id = 'f4000000-0000-7000-8000-000000000001' AND code = 'e2';

UPDATE app.process_stage_def SET
    label = 'Regularización / subsanación',
    short_label = 'Subsanación',
    owner_actor = 'ABOGADO',
    active = TRUE,
    sla_hours = COALESCE(sla_hours, 720)
WHERE process_definition_id = 'f4000000-0000-7000-8000-000000000001' AND code = 'e3';

UPDATE app.process_stage_def SET
    label = 'Minutas y contrato de mutuo',
    short_label = 'Minutas',
    owner_actor = 'ABOGADO',
    active = TRUE,
    sla_hours = COALESCE(sla_hours, 48)
WHERE process_definition_id = 'f4000000-0000-7000-8000-000000000001' AND code = 'e4';

-- Etapas externas: deprecated (datos conservados)
UPDATE app.process_stage_def SET
    owner_actor = 'NOTARIA',
    active = FALSE,
    deprecated_at = COALESCE(deprecated_at, now()),
    label = 'Notaría / matrización (pendiente)',
    short_label = 'Notaría'
WHERE process_definition_id = 'f4000000-0000-7000-8000-000000000001' AND code = 'e5';

UPDATE app.process_stage_def SET
    owner_actor = 'MUNICIPIO',
    active = FALSE,
    deprecated_at = COALESCE(deprecated_at, now()),
    label = 'Liquidación municipal (pendiente)',
    short_label = 'Municipio'
WHERE process_definition_id = 'f4000000-0000-7000-8000-000000000001' AND code = 'e6';

UPDATE app.process_stage_def SET
    owner_actor = 'NOTARIA',
    active = FALSE,
    deprecated_at = COALESCE(deprecated_at, now()),
    label = 'Firmas y cierre notarial (pendiente)',
    short_label = 'Cierre'
WHERE process_definition_id = 'f4000000-0000-7000-8000-000000000001' AND code = 'e7';

UPDATE app.process_stage_def SET
    owner_actor = 'REGISTRO',
    active = FALSE,
    deprecated_at = COALESCE(deprecated_at, now()),
    label = 'Registro de la Propiedad (pendiente)',
    short_label = 'Registro'
WHERE process_definition_id = 'f4000000-0000-7000-8000-000000000001' AND code = 'e8';

UPDATE app.process_definition
SET name = 'Escrituración abogado E1–E4 (externas deprecated)'
WHERE id = 'f4000000-0000-7000-8000-000000000001';

-- ---------------------------------------------------------------------------
-- 3. Expediente: producto BIESS + modo ingesta
-- ---------------------------------------------------------------------------
ALTER TABLE app.legal_case
    ADD COLUMN IF NOT EXISTS product_code VARCHAR(64);

ALTER TABLE app.legal_case
    ADD COLUMN IF NOT EXISTS ingestion_mode VARCHAR(32);

ALTER TABLE app.legal_case
    DROP CONSTRAINT IF EXISTS legal_case_ingestion_mode_chk;

ALTER TABLE app.legal_case
    ADD CONSTRAINT legal_case_ingestion_mode_chk
        CHECK (ingestion_mode IS NULL OR ingestion_mode IN ('DIGITAL_SEPARADO', 'FISICO_ESCANEADO'));

ALTER TABLE app.writing_file
    ADD COLUMN IF NOT EXISTS product_code VARCHAR(64);

ALTER TABLE app.writing_file
    ADD COLUMN IF NOT EXISTS canton VARCHAR(64);

ALTER TABLE app.writing_file
    ADD COLUMN IF NOT EXISTS ingestion_mode VARCHAR(32);

ALTER TABLE app.writing_file
    DROP CONSTRAINT IF EXISTS writing_file_ingestion_mode_chk;

ALTER TABLE app.writing_file
    ADD CONSTRAINT writing_file_ingestion_mode_chk
        CHECK (ingestion_mode IS NULL OR ingestion_mode IN ('DIGITAL_SEPARADO', 'FISICO_ESCANEADO'));

-- ---------------------------------------------------------------------------
-- 4. Catálogo productos BIESS
-- ---------------------------------------------------------------------------
INSERT INTO app.catalog (id, tenant_id, code, name) VALUES
    ('f3000000-0000-7000-8000-000000000010',
     'b1000000-0000-7000-8000-000000000001',
     'ESC_PRODUCTO',
     'Productos hipotecarios BIESS — Escrituración')
ON CONFLICT (tenant_id, code) DO NOTHING;

INSERT INTO app.catalog_item (tenant_id, catalog_id, code, label, sort_order) VALUES
    ('b1000000-0000-7000-8000-000000000001', 'f3000000-0000-7000-8000-000000000010',
     'VIV_HIPOTECADA_BIESS', 'Vivienda Hipotecada con BIESS', 1),
    ('b1000000-0000-7000-8000-000000000001', 'f3000000-0000-7000-8000-000000000010',
     'VIV_TERMINADA_IND', 'Vivienda Terminada Individual', 2),
    ('b1000000-0000-7000-8000-000000000001', 'f3000000-0000-7000-8000-000000000010',
     'VIV_TERMINADA_PREF', 'Vivienda Terminada Preferencial', 3),
    ('b1000000-0000-7000-8000-000000000001', 'f3000000-0000-7000-8000-000000000010',
     'VIV_TERMINADA_SOLID', 'Vivienda Terminada Solidaria', 4),
    ('b1000000-0000-7000-8000-000000000001', 'f3000000-0000-7000-8000-000000000010',
     'VIV_TERMINADA_COMPANIA', 'Vivienda Terminada con Compañía', 5),
    ('b1000000-0000-7000-8000-000000000001', 'f3000000-0000-7000-8000-000000000010',
     'SUSTITUCION_HIPOTECA', 'Sustitución de Hipoteca', 6),
    ('b1000000-0000-7000-8000-000000000001', 'f3000000-0000-7000-8000-000000000010',
     'TERRENO_Y_VIVIENDA', 'Terreno y Vivienda Terminada', 7)
ON CONFLICT (catalog_id, code) DO NOTHING;

-- Tipos documentales adicionales (DOC_TYPE = f300…0002)
INSERT INTO app.catalog_item (tenant_id, catalog_id, code, label, sort_order) VALUES
    ('b1000000-0000-7000-8000-000000000001', 'f3000000-0000-7000-8000-000000000002',
     'PREDIAL', 'Impuesto predial / contribución de mejoras', 20),
    ('b1000000-0000-7000-8000-000000000001', 'f3000000-0000-7000-8000-000000000002',
     'CERT_NO_ADEUDAR', 'Certificado de no adeudar al municipio', 21),
    ('b1000000-0000-7000-8000-000000000001', 'f3000000-0000-7000-8000-000000000002',
     'CERT_BOMBEROS', 'Certificado Cuerpo de Bomberos', 22),
    ('b1000000-0000-7000-8000-000000000001', 'f3000000-0000-7000-8000-000000000002',
     'CERT_NO_POSEER_BIENES', 'Certificado de no poseer bienes', 23),
    ('b1000000-0000-7000-8000-000000000001', 'f3000000-0000-7000-8000-000000000002',
     'RUC', 'RUC persona jurídica', 24),
    ('b1000000-0000-7000-8000-000000000001', 'f3000000-0000-7000-8000-000000000002',
     'NOMBRAMIENTO_RL', 'Nombramiento representante legal', 25),
    ('b1000000-0000-7000-8000-000000000001', 'f3000000-0000-7000-8000-000000000002',
     'ACTA_JUNTA', 'Acta de junta que aprueba la venta', 26),
    ('b1000000-0000-7000-8000-000000000001', 'f3000000-0000-7000-8000-000000000002',
     'LIQUIDACION_HIPOTECA_ANT', 'Liquidación / certificado hipoteca anterior', 27),
    ('b1000000-0000-7000-8000-000000000001', 'f3000000-0000-7000-8000-000000000002',
     'CONTRATO_MUTUO', 'Contrato de mutuo (generado)', 28)
ON CONFLICT (catalog_id, code) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 5. Matriz canónica producto × documento × cantón
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS app.document_requirement (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES control.tenant (id),
    product_code        VARCHAR(64) NOT NULL,
    document_type_code  VARCHAR(64) NOT NULL,
    is_mandatory        BOOLEAN NOT NULL DEFAULT TRUE,
    max_validity_days   INTEGER NOT NULL DEFAULT 60,
    canton              VARCHAR(64) NOT NULL DEFAULT 'ALL',
    description         TEXT,
    sort_order          INTEGER NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id, tenant_id),
    UNIQUE (tenant_id, product_code, document_type_code, canton)
);

CREATE INDEX IF NOT EXISTS ix_document_requirement_product
    ON app.document_requirement (tenant_id, product_code);

-- Seed ALL-canton por producto
INSERT INTO app.document_requirement
    (tenant_id, product_code, document_type_code, is_mandatory, max_validity_days, canton, description, sort_order)
SELECT 'b1000000-0000-7000-8000-000000000001', v.product_code, v.doc_code, v.mandatory, 60, 'ALL', v.descr, v.sort_order
FROM (VALUES
    -- Vivienda hipotecada BIESS
    ('VIV_HIPOTECADA_BIESS', 'AVALUO', TRUE, 'Avalúo comercial', 1),
    ('VIV_HIPOTECADA_BIESS', 'HISTORIA_DOMINIO', TRUE, 'Certificado historia de dominio', 2),
    ('VIV_HIPOTECADA_BIESS', 'ESCRITURA_ANTECEDENTE', TRUE, 'Escritura de adquisición', 3),
    ('VIV_HIPOTECADA_BIESS', 'CEDULA', TRUE, 'Cédula solicitante y vendedor', 4),
    ('VIV_HIPOTECADA_BIESS', 'PAPELETA', TRUE, 'Papeleta de votación', 5),
    -- Terminada individual
    ('VIV_TERMINADA_IND', 'CEDULA', TRUE, 'Cédula comprador y vendedor', 1),
    ('VIV_TERMINADA_IND', 'PAPELETA', TRUE, 'Papeleta de votación', 2),
    ('VIV_TERMINADA_IND', 'AVALUO', TRUE, 'Certificado de avalúo', 3),
    ('VIV_TERMINADA_IND', 'HISTORIA_DOMINIO', TRUE, 'Historia de dominio', 4),
    ('VIV_TERMINADA_IND', 'PREDIAL', TRUE, 'Predial / contribución mejoras', 5),
    -- Preferencial
    ('VIV_TERMINADA_PREF', 'CEDULA', TRUE, 'Cédula comprador y vendedor', 1),
    ('VIV_TERMINADA_PREF', 'PAPELETA', TRUE, 'Papeleta de votación', 2),
    ('VIV_TERMINADA_PREF', 'AVALUO', TRUE, 'Certificado de avalúo', 3),
    ('VIV_TERMINADA_PREF', 'HISTORIA_DOMINIO', TRUE, 'Historia de dominio', 4),
    ('VIV_TERMINADA_PREF', 'CERT_NO_POSEER_BIENES', TRUE, 'No poseer bienes (preferencial)', 5),
    ('VIV_TERMINADA_PREF', 'PREDIAL', TRUE, 'Predial / contribución mejoras', 6),
    -- Solidaria
    ('VIV_TERMINADA_SOLID', 'CEDULA', TRUE, 'Cédulas compradores y vendedores', 1),
    ('VIV_TERMINADA_SOLID', 'PAPELETA', TRUE, 'Papeletas de votación', 2),
    ('VIV_TERMINADA_SOLID', 'AVALUO', TRUE, 'Certificado de avalúo', 3),
    ('VIV_TERMINADA_SOLID', 'HISTORIA_DOMINIO', TRUE, 'Historia de dominio', 4),
    -- Con compañía
    ('VIV_TERMINADA_COMPANIA', 'AVALUO', TRUE, 'Avalúo', 1),
    ('VIV_TERMINADA_COMPANIA', 'HISTORIA_DOMINIO', TRUE, 'Historia de dominio', 2),
    ('VIV_TERMINADA_COMPANIA', 'CEDULA', TRUE, 'Cédula comprador / RL', 3),
    ('VIV_TERMINADA_COMPANIA', 'PAPELETA', TRUE, 'Papeleta RL', 4),
    ('VIV_TERMINADA_COMPANIA', 'RUC', TRUE, 'RUC vendedor compañía', 5),
    ('VIV_TERMINADA_COMPANIA', 'NOMBRAMIENTO_RL', TRUE, 'Nombramiento representante legal', 6),
    ('VIV_TERMINADA_COMPANIA', 'ACTA_JUNTA', TRUE, 'Acta junta aprueba venta', 7),
    ('VIV_TERMINADA_COMPANIA', 'CERT_NO_POSEER_BIENES', FALSE, 'No poseer bienes si preferencial', 8),
    -- Sustitución
    ('SUSTITUCION_HIPOTECA', 'CEDULA', TRUE, 'Cédula deudor', 1),
    ('SUSTITUCION_HIPOTECA', 'PAPELETA', TRUE, 'Papeleta', 2),
    ('SUSTITUCION_HIPOTECA', 'HISTORIA_DOMINIO', TRUE, 'Historia de dominio', 3),
    ('SUSTITUCION_HIPOTECA', 'LIQUIDACION_HIPOTECA_ANT', TRUE, 'Liquidación hipoteca anterior', 4),
    -- Terreno
    ('TERRENO_Y_VIVIENDA', 'CEDULA', TRUE, 'Cédula', 1),
    ('TERRENO_Y_VIVIENDA', 'PAPELETA', TRUE, 'Papeleta', 2),
    ('TERRENO_Y_VIVIENDA', 'AVALUO', TRUE, 'Avalúo', 3),
    ('TERRENO_Y_VIVIENDA', 'HISTORIA_DOMINIO', TRUE, 'Historia de dominio', 4),
    ('TERRENO_Y_VIVIENDA', 'PREDIAL', TRUE, 'Predial', 5)
) AS v(product_code, doc_code, mandatory, descr, sort_order)
ON CONFLICT (tenant_id, product_code, document_type_code, canton) DO NOTHING;

-- Extras por cantón (Daule / Samborondón / Durán) — todos los productos de vivienda
INSERT INTO app.document_requirement
    (tenant_id, product_code, document_type_code, is_mandatory, max_validity_days, canton, description, sort_order)
SELECT 'b1000000-0000-7000-8000-000000000001', p.product_code, d.doc_code, TRUE, 60, c.canton, d.descr, d.sort_order
FROM (VALUES
    ('VIV_HIPOTECADA_BIESS'),
    ('VIV_TERMINADA_IND'),
    ('VIV_TERMINADA_PREF'),
    ('VIV_TERMINADA_SOLID'),
    ('VIV_TERMINADA_COMPANIA'),
    ('TERRENO_Y_VIVIENDA')
) AS p(product_code)
CROSS JOIN (VALUES
    ('DAULE'), ('SAMBORONDON'), ('DURAN')
) AS c(canton)
CROSS JOIN (VALUES
    ('CERT_NO_ADEUDAR', 'Certificado no adeudar municipio', 90),
    ('CERT_BOMBEROS', 'Certificado Cuerpo de Bomberos', 91),
    ('PREDIAL', 'Predial y contribución de mejoras', 92)
) AS d(doc_code, descr, sort_order)
ON CONFLICT (tenant_id, product_code, document_type_code, canton) DO NOTHING;

ALTER TABLE app.document_requirement ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON app.document_requirement;
CREATE POLICY tenant_isolation ON app.document_requirement
    USING (tenant_id = app.current_tenant_id())
    WITH CHECK (tenant_id = app.current_tenant_id());

GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE app.document_requirement TO lexia_app;
GRANT SELECT ON TABLE app.document_requirement TO lexia_readonly;

-- ---------------------------------------------------------------------------
-- 6. Plantilla de formato generable por producto (minuta / mutuo)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS app.product_template (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES control.tenant (id),
    product_code    VARCHAR(64) NOT NULL,
    template_kind   VARCHAR(32) NOT NULL
        CHECK (template_kind IN ('MINUTA_COMPRAVENTA', 'MINUTA_HIPOTECA', 'CONTRATO_MUTUO')),
    label           VARCHAR(200) NOT NULL,
    storage_key     VARCHAR(512),
    company_supplies_cv BOOLEAN NOT NULL DEFAULT FALSE,
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order      INTEGER NOT NULL DEFAULT 0,
    UNIQUE (id, tenant_id),
    UNIQUE (tenant_id, product_code, template_kind)
);

INSERT INTO app.product_template
    (tenant_id, product_code, template_kind, label, company_supplies_cv, sort_order)
VALUES
    ('b1000000-0000-7000-8000-000000000001', 'VIV_HIPOTECADA_BIESS', 'CONTRATO_MUTUO',
     'Contrato mutuo vivienda hipotecada', FALSE, 1),
    ('b1000000-0000-7000-8000-000000000001', 'VIV_HIPOTECADA_BIESS', 'MINUTA_COMPRAVENTA',
     'Minuta compraventa & hipoteca', FALSE, 2),
    ('b1000000-0000-7000-8000-000000000001', 'VIV_TERMINADA_IND', 'CONTRATO_MUTUO',
     'Contrato mutuo regular', FALSE, 1),
    ('b1000000-0000-7000-8000-000000000001', 'VIV_TERMINADA_IND', 'MINUTA_COMPRAVENTA',
     'Minuta compraventa & hipoteca', FALSE, 2),
    ('b1000000-0000-7000-8000-000000000001', 'VIV_TERMINADA_PREF', 'CONTRATO_MUTUO',
     'Contrato mutuo preferencial', FALSE, 1),
    ('b1000000-0000-7000-8000-000000000001', 'VIV_TERMINADA_PREF', 'MINUTA_COMPRAVENTA',
     'Minuta compraventa & hipoteca preferencial', FALSE, 2),
    ('b1000000-0000-7000-8000-000000000001', 'VIV_TERMINADA_SOLID', 'CONTRATO_MUTUO',
     'Formato mutuo regular/preferencial', FALSE, 1),
    ('b1000000-0000-7000-8000-000000000001', 'VIV_TERMINADA_SOLID', 'MINUTA_COMPRAVENTA',
     'Minuta compraventa & hipoteca solidaria', FALSE, 2),
    ('b1000000-0000-7000-8000-000000000001', 'VIV_TERMINADA_COMPANIA', 'CONTRATO_MUTUO',
     'Contrato mutuo BIESS (abogada)', FALSE, 1),
    ('b1000000-0000-7000-8000-000000000001', 'VIV_TERMINADA_COMPANIA', 'MINUTA_HIPOTECA',
     'Minuta hipoteca (abogada)', FALSE, 2),
    ('b1000000-0000-7000-8000-000000000001', 'VIV_TERMINADA_COMPANIA', 'MINUTA_COMPRAVENTA',
     'Minuta compraventa (suministra compañía)', TRUE, 3),
    ('b1000000-0000-7000-8000-000000000001', 'SUSTITUCION_HIPOTECA', 'CONTRATO_MUTUO',
     'Contrato mutuo sustitución de hipoteca', FALSE, 1),
    ('b1000000-0000-7000-8000-000000000001', 'SUSTITUCION_HIPOTECA', 'MINUTA_HIPOTECA',
     'Minuta de hipoteca', FALSE, 2),
    ('b1000000-0000-7000-8000-000000000001', 'TERRENO_Y_VIVIENDA', 'CONTRATO_MUTUO',
     'Contrato vivienda terminada y terreno', FALSE, 1),
    ('b1000000-0000-7000-8000-000000000001', 'TERRENO_Y_VIVIENDA', 'MINUTA_COMPRAVENTA',
     'Minuta compraventa & hipoteca terreno', FALSE, 2)
ON CONFLICT (tenant_id, product_code, template_kind) DO NOTHING;

ALTER TABLE app.product_template ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON app.product_template;
CREATE POLICY tenant_isolation ON app.product_template
    USING (tenant_id = app.current_tenant_id())
    WITH CHECK (tenant_id = app.current_tenant_id());

GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE app.product_template TO lexia_app;
GRANT SELECT ON TABLE app.product_template TO lexia_readonly;

-- Minuta: tipo de plantilla
ALTER TABLE app.minuta_draft
    ADD COLUMN IF NOT EXISTS template_kind VARCHAR(32);

ALTER TABLE app.minuta_draft
    ADD COLUMN IF NOT EXISTS product_code VARCHAR(64);
