-- Identidad global, membresías, RBAC, sesiones, MFA, catálogos, parámetros y auditoría.

CREATE TABLE app.app_user (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email           VARCHAR(320) NOT NULL,
    display_name    VARCHAR(160) NOT NULL,
    status          VARCHAR(24) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'DISABLED', 'LOCKED')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ,
    row_version     BIGINT NOT NULL DEFAULT 1,
    UNIQUE (email)
);

CREATE TABLE app.user_credential (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL UNIQUE REFERENCES app.app_user (id),
    password_hash   VARCHAR(255) NOT NULL,
    algorithm       VARCHAR(32) NOT NULL DEFAULT 'argon2id',
    last_changed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    must_change     BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE app.password_history (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES app.app_user (id),
    password_hash   VARCHAR(255) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_password_history_user ON app.password_history (user_id, created_at DESC);

CREATE TABLE app.mfa_factor (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL REFERENCES app.app_user (id),
    factor_type         VARCHAR(24) NOT NULL DEFAULT 'TOTP'
        CHECK (factor_type IN ('TOTP')),
    secret_encrypted    TEXT NOT NULL,
    enabled             BOOLEAN NOT NULL DEFAULT FALSE,
    confirmed_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, factor_type)
);

CREATE TABLE app.recovery_code (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES app.app_user (id),
    code_hash   VARCHAR(255) NOT NULL,
    used_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE app.legal_entity (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES control.tenant (id),
    nit             VARCHAR(32),
    legal_name      VARCHAR(240) NOT NULL,
    trade_name      VARCHAR(240),
    entity_type     VARCHAR(32) NOT NULL DEFAULT 'COMPANY'
        CHECK (entity_type IN ('COMPANY', 'NOTARY', 'PUBLIC_ENTITY', 'BRANCH')),
    domicile        VARCHAR(320),
    status          VARCHAR(24) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      UUID,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by      UUID,
    deleted_at      TIMESTAMPTZ,
    row_version     BIGINT NOT NULL DEFAULT 1,
    UNIQUE (id, tenant_id)
);

CREATE INDEX ix_legal_entity_tenant ON app.legal_entity (tenant_id, status);

CREATE TABLE app.office (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES control.tenant (id),
    legal_entity_id UUID NOT NULL,
    name            VARCHAR(200) NOT NULL,
    address         VARCHAR(320),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ,
    row_version     BIGINT NOT NULL DEFAULT 1,
    UNIQUE (id, tenant_id),
    FOREIGN KEY (legal_entity_id, tenant_id)
        REFERENCES app.legal_entity (id, tenant_id)
);

CREATE TABLE app.membership (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES app.app_user (id),
    tenant_id       UUID NOT NULL REFERENCES control.tenant (id),
    legal_entity_id UUID,
    status          VARCHAR(24) NOT NULL DEFAULT 'INVITED'
        CHECK (status IN ('INVITED', 'ACTIVE', 'SUSPENDED')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ,
    row_version     BIGINT NOT NULL DEFAULT 1,
    UNIQUE (id, tenant_id),
    UNIQUE (user_id, tenant_id, legal_entity_id),
    FOREIGN KEY (legal_entity_id, tenant_id)
        REFERENCES app.legal_entity (id, tenant_id)
);

CREATE INDEX ix_membership_tenant_user ON app.membership (tenant_id, user_id);

CREATE TABLE app.role (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL REFERENCES control.tenant (id),
    code        VARCHAR(64) NOT NULL,
    name        VARCHAR(160) NOT NULL,
    is_system   BOOLEAN NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at  TIMESTAMPTZ,
    row_version BIGINT NOT NULL DEFAULT 1,
    UNIQUE (id, tenant_id),
    UNIQUE (tenant_id, code)
);

CREATE TABLE app.permission (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code        VARCHAR(96) NOT NULL UNIQUE,
    name        VARCHAR(200) NOT NULL,
    module_code VARCHAR(64) NOT NULL
);

CREATE TABLE app.role_permission (
    role_id         UUID NOT NULL,
    permission_id   UUID NOT NULL REFERENCES app.permission (id),
    tenant_id       UUID NOT NULL REFERENCES control.tenant (id),
    PRIMARY KEY (role_id, permission_id),
    FOREIGN KEY (role_id, tenant_id) REFERENCES app.role (id, tenant_id)
);

CREATE TABLE app.membership_role (
    membership_id   UUID NOT NULL,
    role_id         UUID NOT NULL,
    tenant_id       UUID NOT NULL REFERENCES control.tenant (id),
    PRIMARY KEY (membership_id, role_id),
    FOREIGN KEY (membership_id, tenant_id) REFERENCES app.membership (id, tenant_id),
    FOREIGN KEY (role_id, tenant_id) REFERENCES app.role (id, tenant_id)
);

CREATE TABLE app.user_session (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES app.app_user (id),
    tenant_id       UUID REFERENCES control.tenant (id),
    membership_id   UUID,
    ip_address      VARCHAR(64),
    user_agent      VARCHAR(512),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ NOT NULL,
    revoked_at      TIMESTAMPTZ
);

CREATE TABLE app.refresh_token (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id      UUID NOT NULL REFERENCES app.user_session (id),
    token_hash      VARCHAR(255) NOT NULL UNIQUE,
    family_id       UUID NOT NULL,
    expires_at      TIMESTAMPTZ NOT NULL,
    revoked_at      TIMESTAMPTZ,
    replaced_by     UUID
);

CREATE TABLE app.login_attempt (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email       VARCHAR(320) NOT NULL,
    ip_address  VARCHAR(64),
    success     BOOLEAN NOT NULL,
    reason      VARCHAR(64),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_login_attempt_email_time ON app.login_attempt (email, created_at DESC);

CREATE TABLE app.catalog (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL REFERENCES control.tenant (id),
    code        VARCHAR(64) NOT NULL,
    name        VARCHAR(160) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at  TIMESTAMPTZ,
    row_version BIGINT NOT NULL DEFAULT 1,
    UNIQUE (id, tenant_id),
    UNIQUE (tenant_id, code)
);

CREATE TABLE app.catalog_item (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL REFERENCES control.tenant (id),
    catalog_id  UUID NOT NULL,
    code        VARCHAR(64) NOT NULL,
    label       VARCHAR(200) NOT NULL,
    sort_order  INTEGER NOT NULL DEFAULT 0,
    active      BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE (id, tenant_id),
    UNIQUE (catalog_id, code),
    FOREIGN KEY (catalog_id, tenant_id) REFERENCES app.catalog (id, tenant_id)
);

CREATE TABLE app.tenant_parameter (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL REFERENCES control.tenant (id),
    param_key   VARCHAR(96) NOT NULL,
    param_value TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id, tenant_id),
    UNIQUE (tenant_id, param_key)
);

CREATE TABLE app.audit_event (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID REFERENCES control.tenant (id),
    actor_user_id   UUID REFERENCES app.app_user (id),
    actor_type      VARCHAR(32) NOT NULL DEFAULT 'USER'
        CHECK (actor_type IN ('USER', 'SYSTEM', 'PLATFORM_SUPPORT')),
    event           VARCHAR(128) NOT NULL,
    object_type     VARCHAR(64) NOT NULL,
    object_id       UUID,
    result          VARCHAR(32) NOT NULL DEFAULT 'OK'
        CHECK (result IN ('OK', 'DENIED', 'ERROR')),
    correlation_id  UUID,
    evidence_hash   VARCHAR(128),
    ip_address      VARCHAR(64),
    user_agent      VARCHAR(512),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_audit_event_tenant_time ON app.audit_event (tenant_id, created_at DESC);

REVOKE UPDATE, DELETE ON app.audit_event FROM lexia_app;
GRANT SELECT, INSERT ON app.audit_event TO lexia_app;
GRANT SELECT ON app.audit_event TO lexia_readonly;
