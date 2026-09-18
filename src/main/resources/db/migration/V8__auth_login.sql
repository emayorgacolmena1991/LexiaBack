-- Login, retos MFA y bloqueo temporal. No altera reglas jurídicas.

ALTER TABLE app.app_user
    ADD COLUMN IF NOT EXISTS locked_until TIMESTAMPTZ;

CREATE TABLE IF NOT EXISTS app.auth_challenge (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID NOT NULL REFERENCES app.app_user (id),
    purpose      VARCHAR(32) NOT NULL
        CHECK (purpose IN ('LOGIN_MFA', 'MFA_SETUP')),
    expires_at   TIMESTAMPTZ NOT NULL,
    consumed_at  TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_auth_challenge_user ON app.auth_challenge (user_id, purpose, expires_at DESC);
CREATE INDEX IF NOT EXISTS ix_login_attempt_ip_time ON app.login_attempt (ip_address, created_at DESC);

-- La cookie de sesión se resuelve antes de conocer el tenant.
ALTER TABLE app.user_session DISABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON app.user_session;

CREATE OR REPLACE FUNCTION app.active_memberships_for_user(p_user_id UUID)
RETURNS SETOF app.membership
LANGUAGE sql
SECURITY DEFINER
SET search_path = app, pg_temp
AS $$
    SELECT *
    FROM app.membership
    WHERE user_id = p_user_id
      AND status = 'ACTIVE'
      AND deleted_at IS NULL
    ORDER BY created_at;
$$;

REVOKE ALL ON FUNCTION app.active_memberships_for_user(UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app.active_memberships_for_user(UUID) TO lexia_app;

GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE app.auth_challenge TO lexia_app;
GRANT SELECT ON TABLE app.auth_challenge TO lexia_readonly;
