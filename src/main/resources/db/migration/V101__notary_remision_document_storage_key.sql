-- Almacenamiento paso 3: snapshot inmutable de los documentos enviados a notaría.
-- Cada fila guarda la clave del archivo de la versión enviada. Desde el paso 1 las claves nunca
-- se sobrescriben: reemplazar el documento en el bufete crea otra clave y esta sigue intacta.
-- NULL = fila anterior sin backfill: la app lee la clave de app.document_version.
ALTER TABLE control.notary_remision_document
    ADD COLUMN storage_key VARCHAR(512);

COMMENT ON COLUMN control.notary_remision_document.storage_key IS
    'Clave del archivo de la versión enviada (snapshot). NULL: se usa la de app.document_version.';

-- Backfill solo si este rol no queda sujeto a RLS en app.document_version (mismo criterio que V96):
-- dueño de la tabla sin FORCE, superusuario o BYPASSRLS. Con RLS el UPDATE no vería filas.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM pg_class c
          JOIN pg_namespace n ON n.oid = c.relnamespace
          JOIN pg_roles r ON r.rolname = current_user
         WHERE n.nspname = 'app'
           AND c.relname = 'document_version'
           AND (r.rolsuper OR r.rolbypassrls
                OR (c.relowner = r.oid AND NOT c.relforcerowsecurity))
    ) THEN
        UPDATE control.notary_remision_document d
           SET storage_key = v.storage_key
          FROM app.document_version v
         WHERE v.id = d.firm_document_version_id
           AND v.tenant_id = d.firm_tenant_id
           AND d.storage_key IS NULL;
    ELSE
        RAISE WARNING 'V101: % está sujeto a RLS en app.document_version; backfill de storage_key omitido.',
            current_user;
    END IF;
END
$$;
