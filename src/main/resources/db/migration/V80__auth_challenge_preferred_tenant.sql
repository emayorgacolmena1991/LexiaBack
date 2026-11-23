-- Tenant preferido en reto MFA (login con tenantId explícito).
ALTER TABLE app.auth_challenge
    ADD COLUMN IF NOT EXISTS preferred_tenant_id UUID NULL;
