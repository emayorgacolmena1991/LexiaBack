-- EJ-4 (histórico): catálogo DOC_TYPE. V104 renombra a SOPORTE_INGRESOS y lo siembra como
-- requisito opcional en productos con compraventa. No editar el código aquí (checksum Flyway).

INSERT INTO app.catalog_item (tenant_id, catalog_id, code, label, sort_order)
SELECT c.tenant_id, c.id, 'SOPORTE_INGRESO_COMPRADOR',
       'Roles de pago / soportes de ingresos del comprador', 30
FROM app.catalog c
WHERE c.code = 'DOC_TYPE' AND c.deleted_at IS NULL
ON CONFLICT (catalog_id, code) DO NOTHING;
