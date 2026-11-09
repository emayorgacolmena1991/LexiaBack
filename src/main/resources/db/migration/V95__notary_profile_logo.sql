-- Logo de la notaría. V93 ya da SELECT, INSERT, UPDATE sobre la tabla: las columnas nuevas no
-- necesitan GRANT adicional.
ALTER TABLE control.notary_tenant_profile
    ADD COLUMN logo_storage_ref  VARCHAR(1024),
    ADD COLUMN logo_content_type VARCHAR(32)
        CONSTRAINT ck_notary_profile_logo_type CHECK (logo_content_type IN ('image/png', 'image/jpeg')),
    ADD COLUMN logo_updated_at   TIMESTAMPTZ;
