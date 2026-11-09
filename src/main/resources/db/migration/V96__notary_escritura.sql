-- Fase 4A: producto de cada versión y escrituras generadas por la notaría.

-- La sesión de la notaría no puede leer app.minuta_draft (RLS): el producto viaja en la versión.
ALTER TABLE control.notary_remision_version
    ADD COLUMN product_code VARCHAR(64);

-- Backfill solo si este rol no queda sujeto a RLS en app.minuta_draft: dueño de la tabla sin
-- FORCE, superusuario o BYPASSRLS. Con RLS el UPDATE no vería filas y fallaría en silencio.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM pg_class c
          JOIN pg_namespace n ON n.oid = c.relnamespace
          JOIN pg_roles r ON r.rolname = current_user
         WHERE n.nspname = 'app'
           AND c.relname = 'minuta_draft'
           AND (r.rolsuper OR r.rolbypassrls
                OR (c.relowner = r.oid AND NOT c.relforcerowsecurity))
    ) THEN
        UPDATE control.notary_remision_version v
           SET product_code = d.product_code
          FROM app.minuta_draft d
         WHERE d.id = v.minuta_draft_id
           AND v.product_code IS NULL;
    ELSE
        RAISE WARNING 'V96: % está sujeto a RLS en app.minuta_draft; backfill de product_code omitido.',
            current_user;
    END IF;
END
$$;

CREATE TABLE control.notary_escritura (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    remision_id         UUID NOT NULL REFERENCES control.notary_remision (id) ON DELETE CASCADE,
    version_no          INTEGER NOT NULL CHECK (version_no >= 1),
    datos_formulario    JSONB NOT NULL,
    campos_vacios       JSONB NOT NULL,
    storage_ref         VARCHAR(1024) NOT NULL,
    file_name           VARCHAR(500) NOT NULL,
    generated_by        UUID REFERENCES app.app_user (id),
    generated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_notary_escritura_version
        FOREIGN KEY (remision_id, version_no)
        REFERENCES control.notary_remision_version (remision_id, version_no)
);

CREATE INDEX ix_notary_escritura_remision
    ON control.notary_escritura (remision_id, generated_at DESC);

COMMENT ON TABLE control.notary_escritura IS
    'Escrituras generadas por la notaría sobre una versión del envío. Inmutable: cada generación es una fila.';

-- Control solo recibe SELECT por defecto (V93). Sin UPDATE ni DELETE: inmutable.
GRANT SELECT, INSERT ON TABLE control.notary_escritura TO lexia_app;
GRANT SELECT ON TABLE control.notary_escritura TO lexia_readonly;
