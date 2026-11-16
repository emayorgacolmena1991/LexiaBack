-- EJ-2: con vendedor persona jurídica (producto VIV_TERMINADA_COMPANIA) la minuta de compraventa
-- la entrega el tercero; el bufete la sube al expediente. Opcional: no altera expedientes existentes.

INSERT INTO app.catalog_item (tenant_id, catalog_id, code, label, sort_order)
SELECT c.tenant_id, c.id, 'MINUTA_COMPRAVENTA_TERCERO',
       'Minuta de compraventa de tercero (vendedor persona jurídica)', 29
FROM app.catalog c
WHERE c.code = 'DOC_TYPE' AND c.deleted_at IS NULL
ON CONFLICT (catalog_id, code) DO NOTHING;

INSERT INTO app.document_requirement
    (tenant_id, product_code, document_type_code, is_mandatory, max_validity_days, canton, description, sort_order)
SELECT DISTINCT r.tenant_id, 'VIV_TERMINADA_COMPANIA', 'MINUTA_COMPRAVENTA_TERCERO', FALSE, 60, 'ALL',
       'Minuta de compraventa de tercero (vendedor persona jurídica)', 9
FROM app.document_requirement r
WHERE r.product_code = 'VIV_TERMINADA_COMPANIA'
ON CONFLICT (tenant_id, product_code, document_type_code, canton) DO NOTHING;

ALTER TABLE app.document
    ADD COLUMN IF NOT EXISTS uploaded_by UUID,
    ADD COLUMN IF NOT EXISTS observacion VARCHAR(500);
