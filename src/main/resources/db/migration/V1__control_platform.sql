-- Plano de control de plataforma. Sin RLS de tenant.

CREATE SCHEMA IF NOT EXISTS control;
CREATE SCHEMA IF NOT EXISTS app;

COMMENT ON SCHEMA control IS 'Plano LEXIA: tenants, aislamiento, operadores. Sin RLS.';
COMMENT ON SCHEMA app IS 'Datos operativos multi-tenant. RLS por tenant_id.';

GRANT USAGE ON SCHEMA control TO lexia_app, lexia_readonly;
GRANT USAGE ON SCHEMA app TO lexia_app, lexia_readonly;

ALTER DEFAULT PRIVILEGES FOR ROLE lexia_migrator IN SCHEMA control
    GRANT SELECT ON TABLES TO lexia_app, lexia_readonly;
ALTER DEFAULT PRIVILEGES FOR ROLE lexia_migrator IN SCHEMA app
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO lexia_app;
ALTER DEFAULT PRIVILEGES FOR ROLE lexia_migrator IN SCHEMA app
    GRANT SELECT ON TABLES TO lexia_readonly;
ALTER DEFAULT PRIVILEGES FOR ROLE lexia_migrator IN SCHEMA control
    GRANT USAGE, SELECT ON SEQUENCES TO lexia_app, lexia_readonly;
ALTER DEFAULT PRIVILEGES FOR ROLE lexia_migrator IN SCHEMA app
    GRANT USAGE, SELECT ON SEQUENCES TO lexia_app, lexia_readonly;

CREATE TABLE control.platform_role (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code        VARCHAR(64) NOT NULL UNIQUE,
    name        VARCHAR(160) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE control.platform_user (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email               VARCHAR(320) NOT NULL UNIQUE,
    display_name        VARCHAR(160) NOT NULL,
    status              VARCHAR(24) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'DISABLED')),
    platform_role_id    UUID NOT NULL REFERENCES control.platform_role (id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at          TIMESTAMPTZ,
    row_version         BIGINT NOT NULL DEFAULT 1
);

CREATE TABLE control.tenant (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code            VARCHAR(64) NOT NULL UNIQUE,
    name            VARCHAR(200) NOT NULL,
    status          VARCHAR(24) NOT NULL DEFAULT 'PROVISIONING'
        CHECK (status IN ('PROVISIONING', 'ACTIVE', 'SUSPENDED', 'CLOSED')),
    timezone        VARCHAR(64) NOT NULL DEFAULT 'America/Bogota',
    isolation_mode  VARCHAR(32) NOT NULL DEFAULT 'SHARED'
        CHECK (isolation_mode IN ('SHARED', 'DEDICATED_SCHEMA', 'DEDICATED_DB')),
    plan_code       VARCHAR(64),
    max_users       INTEGER,
    max_cases       INTEGER,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ,
    row_version     BIGINT NOT NULL DEFAULT 1
);

CREATE UNIQUE INDEX ux_tenant_id_tenant ON control.tenant (id);

CREATE TABLE control.tenant_module (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL REFERENCES control.tenant (id),
    module_code VARCHAR(64) NOT NULL
        CHECK (module_code IN (
            'OPERACION_LEGAL', 'NOTARIA', 'COMPLIANCE', 'LITIGIO', 'CONSULTORIA'
        )),
    enabled     BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, module_code)
);

CREATE TABLE control.tenant_isolation_target (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL UNIQUE REFERENCES control.tenant (id),
    schema_name     VARCHAR(63),
    database_name   VARCHAR(63),
    host            VARCHAR(255),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE control.provisioning_job (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL REFERENCES control.tenant (id),
    job_type    VARCHAR(64) NOT NULL,
    status      VARCHAR(24) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    detail      TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ
);

CREATE OR REPLACE FUNCTION app.current_tenant_id()
RETURNS UUID
LANGUAGE plpgsql
STABLE
AS $$
DECLARE
    raw TEXT;
BEGIN
    raw := current_setting('app.current_tenant_id', true);
    IF raw IS NULL OR raw = '' THEN
        RETURN NULL;
    END IF;
    RETURN raw::uuid;
END;
$$;

COMMENT ON FUNCTION app.current_tenant_id() IS
    'Tenant del request: set_config(''app.current_tenant_id'', uuid, false|true).';

GRANT EXECUTE ON FUNCTION app.current_tenant_id() TO lexia_app, lexia_readonly;
