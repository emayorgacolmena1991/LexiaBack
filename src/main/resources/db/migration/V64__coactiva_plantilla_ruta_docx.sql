-- Cada plantilla de Coactivas resuelve su .docx por ruta de classpath, nunca por el nombre visible.
-- Sin ruta_docx la plantilla se lista pero no se puede generar.

ALTER TABLE app.coactiva_plantilla
    ADD COLUMN ruta_docx VARCHAR(255);

ALTER TABLE app.coactiva_plantilla
    ADD CONSTRAINT coactiva_plantilla_ruta_docx_check
        CHECK (ruta_docx IS NULL
               OR (ruta_docx LIKE 'templates/coactivas/%.docx' AND position('..' IN ruta_docx) = 0));

-- La providencia de embargo de valores (antes "PROV EMBARGO BANCO GYE") usa la plantilla genérica por zonal.
INSERT INTO app.coactiva_plantilla (tenant_id, codigo, nombre, etapa, ruta_docx)
SELECT t.id,
       'embargo-banco',
       'FORMATO PROVIDENCIA DE EMBARGO.docx',
       'EMBARGO',
       'templates/coactivas/FORMATO_PROV_EMBARGO_TEMPLATE.docx'
FROM control.tenant t
WHERE t.deleted_at IS NULL
ON CONFLICT (tenant_id, codigo) DO UPDATE
    SET nombre    = EXCLUDED.nombre,
        etapa     = EXCLUDED.etapa,
        ruta_docx = EXCLUDED.ruta_docx,
        activo    = TRUE;
