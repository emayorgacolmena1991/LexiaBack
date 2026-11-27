-- Escrituración, fase 4: comparecientes múltiples del expediente.
-- - app.expediente_compareciente: compradores, vendedores, cónyuges, representantes, apoderados y
--   herederos de un borrador o expediente (expediente_id = id de cualquiera de los dos, igual que
--   app.expediente_condicion; al promover el borrador las filas pasan al expediente).
--   Se elige tabla nueva y no app.case_party: case_party exige legal_case (no sirve al borrador),
--   solo distingue CLIENT/PARTY/OTHER y no guarda estado civil, nacimiento ni discapacidad.
-- - app.document.compareciente_id: liga cédula, papeleta, nombramiento o poder a una persona.

CREATE TABLE IF NOT EXISTS app.expediente_compareciente (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID NOT NULL REFERENCES control.tenant (id),
    expediente_id     UUID NOT NULL,
    -- Lado del contrato: REPRESENTANTE y APODERADO no lo dicen por sí solos.
    parte             VARCHAR(10) NOT NULL CHECK (parte IN ('COMPRADORA', 'VENDEDORA')),
    rol               VARCHAR(20) NOT NULL CHECK (rol IN (
                          'COMPRADOR', 'VENDEDOR', 'CONYUGE_COMPRADOR', 'CONYUGE_VENDEDOR',
                          'REPRESENTANTE', 'APODERADO', 'HEREDERO')),
    orden             INTEGER NOT NULL DEFAULT 0,
    nombres           VARCHAR(240) NOT NULL,
    identificacion    VARCHAR(20),
    estado_civil      VARCHAR(40),
    profesion         VARCHAR(120),
    fecha_nacimiento  DATE,
    -- NULL = el documento no lo dice (nunca se asume NO).
    discapacidad      BOOLEAN,
    persona_juridica  BOOLEAN NOT NULL DEFAULT FALSE,
    con_apoderado     BOOLEAN NOT NULL DEFAULT FALSE,
    origen            VARCHAR(8) NOT NULL CHECK (origen IN ('IA', 'MANUAL')),
    actualizado_por   UUID,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id, tenant_id)
);

CREATE INDEX IF NOT EXISTS ix_expediente_compareciente_expediente
    ON app.expediente_compareciente (tenant_id, expediente_id, parte, orden);

ALTER TABLE app.expediente_compareciente ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON app.expediente_compareciente;
CREATE POLICY tenant_isolation ON app.expediente_compareciente
    USING (tenant_id = app.current_tenant_id())
    WITH CHECK (tenant_id = app.current_tenant_id());

GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE app.expediente_compareciente TO lexia_app;
GRANT SELECT ON TABLE app.expediente_compareciente TO lexia_readonly;

ALTER TABLE app.document ADD COLUMN IF NOT EXISTS compareciente_id UUID;

ALTER TABLE app.document DROP CONSTRAINT IF EXISTS fk_document_compareciente;
ALTER TABLE app.document
    ADD CONSTRAINT fk_document_compareciente
        FOREIGN KEY (compareciente_id) REFERENCES app.expediente_compareciente (id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS ix_document_compareciente
    ON app.document (compareciente_id) WHERE compareciente_id IS NOT NULL;
