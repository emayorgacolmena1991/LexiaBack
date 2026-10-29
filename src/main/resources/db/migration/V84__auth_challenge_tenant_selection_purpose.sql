-- Permite retos post-login para elegir organización (multi-membresía).

ALTER TABLE app.auth_challenge DROP CONSTRAINT IF EXISTS auth_challenge_purpose_check;
ALTER TABLE app.auth_challenge
    ADD CONSTRAINT auth_challenge_purpose_check
        CHECK (purpose IN ('LOGIN_MFA', 'MFA_SETUP', 'PASSWORD_RESET', 'TENANT_SELECTION'));
