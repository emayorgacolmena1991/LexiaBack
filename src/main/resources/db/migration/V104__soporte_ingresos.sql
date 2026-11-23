-- EJ-4: SOPORTE_INGRESOS como requisito OPCIONAL en productos con compraventa
-- (todos menos SUSTITUCION_HIPOTECA). Idempotente; renombra el código de V103 si existía.

UPDATE app.catalog_item
SET code = 'SOPORTE_INGRESOS',
    label = 'Roles de pago / soportes de ingresos'
WHERE code = 'SOPORTE_INGRESO_COMPRADOR'
  AND NOT EXISTS (
    SELECT 1
    FROM app.catalog_item other
    WHERE other.catalog_id = catalog_item.catalog_id
      AND other.code = 'SOPORTE_INGRESOS'
  );

INSERT INTO app.catalog_item (tenant_id, catalog_id, code, label, sort_order)
SELECT c.tenant_id, c.id, 'SOPORTE_INGRESOS',
       'Roles de pago / soportes de ingresos', 30
FROM app.catalog c
WHERE c.code = 'DOC_TYPE' AND c.deleted_at IS NULL
ON CONFLICT (catalog_id, code) DO NOTHING;

DELETE FROM app.catalog_item WHERE code = 'SOPORTE_INGRESO_COMPRADOR';

UPDATE app.document
SET doc_type = 'SOPORTE_INGRESOS'
WHERE doc_type = 'SOPORTE_INGRESO_COMPRADOR';

UPDATE app.document
SET tipo_sugerido = 'SOPORTE_INGRESOS'
WHERE tipo_sugerido = 'SOPORTE_INGRESO_COMPRADOR';

UPDATE app.documento_texto_ocr
SET tipo_documento = 'SOPORTE_INGRESOS'
WHERE tipo_documento = 'SOPORTE_INGRESO_COMPRADOR';

INSERT INTO app.document_requirement
    (tenant_id, product_code, document_type_code, is_mandatory, max_validity_days, canton, description, sort_order)
SELECT DISTINCT
    r.tenant_id,
    r.product_code,
    'SOPORTE_INGRESOS',
    FALSE,
    60,
    r.canton,
    'Roles de pago / soportes de ingresos',
    95
FROM app.document_requirement r
WHERE r.product_code <> 'SUSTITUCION_HIPOTECA'
ON CONFLICT (tenant_id, product_code, document_type_code, canton) DO NOTHING;
