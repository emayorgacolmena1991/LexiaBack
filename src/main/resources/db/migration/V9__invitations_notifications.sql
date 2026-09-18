-- Invitaciones de usuario, reset de contraseña y preferencias de notificación.

ALTER TABLE app.app_user
    ADD COLUMN IF NOT EXISTS notify_on_login BOOLEAN NOT NULL DEFAULT TRUE;

CREATE TABLE IF NOT EXISTS app.user_invitation (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES control.tenant (id),
    email           VARCHAR(320) NOT NULL,
    display_name    VARCHAR(160) NOT NULL,
    token_hash      VARCHAR(64) NOT NULL,
    invited_by      UUID REFERENCES app.app_user (id),
    membership_id   UUID NOT NULL REFERENCES app.membership (id),
    expires_at      TIMESTAMPTZ NOT NULL,
    accepted_at     TIMESTAMPTZ,
    revoked_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_user_invitation_token ON app.user_invitation (token_hash);
CREATE UNIQUE INDEX IF NOT EXISTS ux_user_invitation_pending
    ON app.user_invitation (tenant_id, lower(email))
    WHERE accepted_at IS NULL AND revoked_at IS NULL;

CREATE INDEX IF NOT EXISTS ix_user_invitation_membership ON app.user_invitation (membership_id);

ALTER TABLE app.auth_challenge DROP CONSTRAINT IF EXISTS auth_challenge_purpose_check;
ALTER TABLE app.auth_challenge
    ADD CONSTRAINT auth_challenge_purpose_check
        CHECK (purpose IN ('LOGIN_MFA', 'MFA_SETUP', 'PASSWORD_RESET'));

GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE app.user_invitation TO lexia_app;
GRANT SELECT ON TABLE app.user_invitation TO lexia_readonly;
