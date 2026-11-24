-- Formatos de acta coactiva recordados por tenant: cada delegado/oficina arma el acta distinto.
-- La firma es el hash de la fila de cabecera normalizada; el mapeo dice qué columna es qué campo.

ALTER TABLE app.coactiva_acta
    ADD COLUMN IF NOT EXISTS tablas_json JSONB;

COMMENT ON COLUMN app.coactiva_acta.tablas_json IS
    'Tablas leídas del archivo (Excel u OCR) para volver a mapear columnas sin repetir el OCR.';

CREATE TABLE app.coactiva_acta_formato (
    id           UUID PRIMARY KEY,
    tenant_id    UUID NOT NULL,
    firma        VARCHAR(64) NOT NULL,
    nombre       VARCHAR(160),
    cabecera     JSONB NOT NULL,
    mapeo        JSONB NOT NULL,
    usos         INTEGER NOT NULL DEFAULT 0,
    created_by   UUID,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT coactiva_acta_formato_firma_uq UNIQUE (tenant_id, firma)
);

COMMENT ON COLUMN app.coactiva_acta_formato.mapeo IS
    'Objeto {"<indice de columna>": "<CAMPO>"}; CAMPO en JUICIO, DEUDOR, CEDULA, OPERACION, OFICINA, ETAPA, FOJAS, ANIO, ZONAL, UEC.';

ALTER TABLE app.coactiva_acta_formato ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS tenant_isolation ON app.coactiva_acta_formato;

CREATE POLICY tenant_isolation ON app.coactiva_acta_formato
    USING (tenant_id = app.current_tenant_id())
    WITH CHECK (tenant_id = app.current_tenant_id());

GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE app.coactiva_acta_formato TO lexia_app;
GRANT SELECT ON TABLE app.coactiva_acta_formato TO lexia_readonly;
