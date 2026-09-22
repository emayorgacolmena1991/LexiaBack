-- Notificaciones in-app por usuario (centro admin / configuración).

CREATE TABLE app.user_notification (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES control.tenant (id),
    user_id         UUID NOT NULL REFERENCES app.app_user (id),
    category        VARCHAR(16) NOT NULL DEFAULT 'ADMIN'
        CHECK (category IN ('ADMIN', 'SYSTEM')),
    type            VARCHAR(64) NOT NULL,
    params          JSONB NOT NULL DEFAULT '{}'::jsonb,
    href            VARCHAR(512),
    dedupe_key      VARCHAR(160),
    read_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id, tenant_id),
    UNIQUE (tenant_id, user_id, dedupe_key)
);

CREATE INDEX ix_user_notification_inbox
    ON app.user_notification (tenant_id, user_id, read_at, created_at DESC);
