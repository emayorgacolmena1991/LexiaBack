CREATE TABLE app.jobs (
    id          UUID PRIMARY KEY,
    tenant_id   UUID NOT NULL,
    tipo        VARCHAR(50) NOT NULL,
    payload     JSONB NOT NULL,
    estado      VARCHAR(20) NOT NULL DEFAULT 'PENDIENTE',
    intentos    INT NOT NULL DEFAULT 0,
    error_log   TEXT,
    run_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_jobs_pendientes ON app.jobs (estado, run_at, created_at);

GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE app.jobs TO lexia_app;
GRANT SELECT ON TABLE app.jobs TO lexia_readonly;

CREATE TABLE app.documento_cache (
    id              UUID PRIMARY KEY,
    tenant_id       UUID NOT NULL,
    sha256          CHAR(64) NOT NULL,
    version_prompt  VARCHAR(64) NOT NULL,
    modelo          VARCHAR(80) NOT NULL,
    texto_ocr       TEXT,
    resultado_json  TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, sha256, version_prompt, modelo)
);

ALTER TABLE app.documento_cache ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON app.documento_cache;
CREATE POLICY tenant_isolation ON app.documento_cache
    USING (tenant_id = app.current_tenant_id())
    WITH CHECK (tenant_id = app.current_tenant_id());

GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE app.documento_cache TO lexia_app;
GRANT SELECT ON TABLE app.documento_cache TO lexia_readonly;

CREATE TABLE app.ia_log_consumo (
    id                  UUID PRIMARY KEY,
    tenant_id           UUID NOT NULL,
    expediente_id       UUID,
    modelo_usado        VARCHAR(80) NOT NULL,
    prompt_tokens       INT NOT NULL,
    completion_tokens   INT NOT NULL,
    costo_estimado      NUMERIC(12, 6) NOT NULL,
    tiempo_respuesta_ms BIGINT NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE app.ia_log_consumo ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON app.ia_log_consumo;
CREATE POLICY tenant_isolation ON app.ia_log_consumo
    USING (tenant_id = app.current_tenant_id())
    WITH CHECK (tenant_id = app.current_tenant_id());

GRANT SELECT, INSERT ON TABLE app.ia_log_consumo TO lexia_app;
GRANT SELECT ON TABLE app.ia_log_consumo TO lexia_readonly;

CREATE TABLE app.escrituracion_prompt_catalog (
    id              BIGSERIAL PRIMARY KEY,
    canton          VARCHAR(64) NOT NULL DEFAULT '*',
    product_code    VARCHAR(64) NOT NULL DEFAULT '*',
    tipo_documento  VARCHAR(64) NOT NULL DEFAULT '*',
    system_prompt   TEXT NOT NULL,
    activo          BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (canton, product_code, tipo_documento)
);

GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE app.escrituracion_prompt_catalog TO lexia_app;
GRANT SELECT ON TABLE app.escrituracion_prompt_catalog TO lexia_readonly;
GRANT USAGE, SELECT ON SEQUENCE app.escrituracion_prompt_catalog_id_seq TO lexia_app;
