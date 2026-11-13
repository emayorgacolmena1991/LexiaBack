-- Debida diligencia de fondos: el snapshot de cada documento enviado a notaría guarda su tipo
-- (p. ej. SOPORTE_INGRESOS, V104). Así la notaría filtra por tipo sin leer app.document del bufete.
-- NULL = fila anterior sin backfill o documento sin tipo.
ALTER TABLE control.notary_remision_document
    ADD COLUMN doc_type VARCHAR(80);

COMMENT ON COLUMN control.notary_remision_document.doc_type IS
    'Tipo del documento (app.document.doc_type) al momento del envío. NULL: sin tipo o sin backfill.';

-- Backfill solo si este rol no queda sujeto a RLS en app.document_version ni en app.document
-- (mismo criterio que V96/V101): dueño de la tabla sin FORCE, superusuario o BYPASSRLS.
DO $$
DECLARE
    legibles INTEGER;
BEGIN
    SELECT count(*)
      INTO legibles
      FROM pg_class c
      JOIN pg_namespace n ON n.oid = c.relnamespace
      JOIN pg_roles r ON r.rolname = current_user
     WHERE n.nspname = 'app'
       AND c.relname IN ('document_version', 'document')
       AND (r.rolsuper OR r.rolbypassrls
            OR (c.relowner = r.oid AND NOT c.relforcerowsecurity));

    IF legibles = 2 THEN
        UPDATE control.notary_remision_document d
           SET doc_type = doc.doc_type
          FROM app.document_version v
          JOIN app.document doc
            ON doc.id = v.document_id
           AND doc.tenant_id = v.tenant_id
         WHERE v.id = d.firm_document_version_id
           AND v.tenant_id = d.firm_tenant_id
           AND d.doc_type IS NULL
           AND doc.doc_type IS NOT NULL;
    ELSE
        RAISE WARNING 'V105: % está sujeto a RLS en app.document_version o app.document; backfill de doc_type omitido.',
            current_user;
    END IF;
END
$$;
