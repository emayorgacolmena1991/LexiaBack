-- S1: app.documento_texto_ocr no tenía RLS y tenant_id admitía NULL. Cualquier sesión leía el OCR
-- de cualquier expediente sabiendo su id.

-- 1) Backfill de tenant_id desde el borrador o el expediente. Solo si este rol ve todas las filas
--    (dueño sin FORCE, superusuario o BYPASSRLS), mismo criterio que V96/V101.
DO $$
BEGIN
    IF (
        SELECT count(*)
          FROM pg_class c
          JOIN pg_namespace n ON n.oid = c.relnamespace
          JOIN pg_roles r ON r.rolname = current_user
         WHERE n.nspname = 'app'
           AND c.relname IN ('expediente_borrador', 'legal_case')
           AND (r.rolsuper OR r.rolbypassrls
                OR (c.relowner = r.oid AND NOT c.relforcerowsecurity))
    ) = 2 THEN
        UPDATE app.documento_texto_ocr o
           SET tenant_id = b.tenant_id
          FROM app.expediente_borrador b
         WHERE o.tenant_id IS NULL
           AND o.id_expediente = b.id::text;

        UPDATE app.documento_texto_ocr o
           SET tenant_id = c.tenant_id
          FROM app.legal_case c
         WHERE o.tenant_id IS NULL
           AND o.id_expediente = c.id::text;
    ELSE
        RAISE WARNING 'V109: % está sujeto a RLS en expediente_borrador/legal_case; backfill de tenant_id omitido.',
            current_user;
    END IF;
END
$$;

-- 2) Filas que siguen sin tenant: con RLS dejan de verse. Se informan, no se borran.
DO $$
DECLARE
    huerfanas BIGINT;
BEGIN
    SELECT count(*) INTO huerfanas FROM app.documento_texto_ocr WHERE tenant_id IS NULL;
    IF huerfanas > 0 THEN
        RAISE WARNING 'V109: % filas de app.documento_texto_ocr sin tenant_id quedan invisibles para lexia_app.',
            huerfanas;
    END IF;
END
$$;

-- 3) RLS con la misma política que el resto de tablas de app (V6).
ALTER TABLE app.documento_texto_ocr ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON app.documento_texto_ocr;
CREATE POLICY tenant_isolation ON app.documento_texto_ocr
    USING (tenant_id = app.current_tenant_id())
    WITH CHECK (tenant_id = app.current_tenant_id());
