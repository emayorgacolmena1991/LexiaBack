-- Matriz de requisitos por acto. Reusa ESC_OPERATION_TYPE + DOC_TYPE existentes.

INSERT INTO app.catalog_item (tenant_id, catalog_id, code, label, sort_order)
SELECT 'b1000000-0000-7000-8000-000000000001',
       'f3000000-0000-7000-8000-000000000002',
       v.code, v.label, v.sort_order
FROM (VALUES
    ('CEDULA', 'Cédula de Identidad / Ciudadanía', 10),
    ('PAPELETA', 'Papeleta de Votación', 11),
    ('HISTORIA_DOMINIO', 'Certificado de Historia de Dominio', 12),
    ('ESCRITURA_ANTECEDENTE', 'Escritura Antecedente', 13),
    ('PAGO_IMPUESTO', 'Pago de Impuestos Municipales', 14),
    ('CARTA_APROBACION', 'Carta de Aprobación del Crédito', 15)
) AS v(code, label, sort_order)
ON CONFLICT (catalog_id, code) DO NOTHING;

CREATE TABLE IF NOT EXISTS app.act_requirement (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES control.tenant (id),
    act_item_id         UUID NOT NULL,
    document_item_id    UUID NOT NULL,
    required            BOOLEAN NOT NULL DEFAULT TRUE,
    description         TEXT,
    sort_order          INTEGER NOT NULL DEFAULT 0,
    UNIQUE (id, tenant_id),
    UNIQUE (act_item_id, document_item_id),
    FOREIGN KEY (act_item_id, tenant_id) REFERENCES app.catalog_item (id, tenant_id),
    FOREIGN KEY (document_item_id, tenant_id) REFERENCES app.catalog_item (id, tenant_id)
);

CREATE INDEX IF NOT EXISTS ix_act_requirement_act ON app.act_requirement (tenant_id, act_item_id);

INSERT INTO app.act_requirement (tenant_id, act_item_id, document_item_id, required, description, sort_order)
SELECT ids.tenant_id, ids.act_id, ids.doc_id, m.required, m.description, m.sort_order
FROM (VALUES
    ('COMPRAVENTA'::varchar, 'CEDULA'::varchar, TRUE,
     'Documento de identidad del comprador y vendedor', 1),
    ('COMPRAVENTA', 'PAPELETA', TRUE, 'Certificado de votación actualizado', 2),
    ('COMPRAVENTA', 'HISTORIA_DOMINIO', TRUE, 'Certificado del Registro de la Propiedad', 3),
    ('COMPRAVENTA', 'AVALUO', TRUE, 'Comprobante de avalúo del predio', 4),
    ('DONACION', 'CEDULA', TRUE, 'Identificación de donante y donatario', 1),
    ('DONACION', 'PAPELETA', TRUE, 'Certificado de votación', 2),
    ('DONACION', 'HISTORIA_DOMINIO', TRUE, 'Certificado actualizado sin gravámenes', 3),
    ('DONACION', 'ESCRITURA_ANTECEDENTE', TRUE, 'Copia de la escritura previa', 4),
    ('DONACION', 'PAGO_IMPUESTO', TRUE, 'Comprobante de pago al día', 5),
    ('HIPOTECA', 'CEDULA', TRUE, 'Identificación del deudor y acreedor', 1),
    ('HIPOTECA', 'PAPELETA', TRUE, 'Certificado de votación vigente', 2),
    ('HIPOTECA', 'HISTORIA_DOMINIO', TRUE, 'Certificado de gravámenes', 3),
    ('HIPOTECA', 'AVALUO', TRUE, 'Informe técnico de valoración', 4),
    ('HIPOTECA', 'MINUTA', TRUE, 'Borrador de la minuta del crédito hipotecario', 5),
    ('HIPOTECA', 'CARTA_APROBACION', FALSE, 'Emitida por la entidad bancaria', 6)
) AS m(act_code, doc_code, required, description, sort_order)
CROSS JOIN LATERAL (
    SELECT
        'b1000000-0000-7000-8000-000000000001'::uuid AS tenant_id,
        (SELECT i.id FROM app.catalog_item i
          JOIN app.catalog c ON c.id = i.catalog_id
         WHERE c.tenant_id = 'b1000000-0000-7000-8000-000000000001'
           AND c.code = 'ESC_OPERATION_TYPE'
           AND i.code = m.act_code) AS act_id,
        (SELECT i.id FROM app.catalog_item i
          WHERE i.catalog_id = 'f3000000-0000-7000-8000-000000000002'
            AND i.code = m.doc_code) AS doc_id
) ids
WHERE ids.act_id IS NOT NULL AND ids.doc_id IS NOT NULL
ON CONFLICT (act_item_id, document_item_id) DO NOTHING;

ALTER TABLE app.act_requirement ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON app.act_requirement;
CREATE POLICY tenant_isolation ON app.act_requirement
    USING (tenant_id = app.current_tenant_id())
    WITH CHECK (tenant_id = app.current_tenant_id());

GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE app.act_requirement TO lexia_app;
GRANT SELECT ON TABLE app.act_requirement TO lexia_readonly;
