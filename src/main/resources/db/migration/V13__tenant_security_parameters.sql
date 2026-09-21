-- Parámetros de seguridad del tenant (TTL invitación, MFA obligatorio).

INSERT INTO app.tenant_parameter (tenant_id, param_key, param_value)
SELECT id, 'invite_ttl_hours', '72'
FROM control.tenant
WHERE deleted_at IS NULL
ON CONFLICT (tenant_id, param_key) DO NOTHING;

INSERT INTO app.tenant_parameter (tenant_id, param_key, param_value)
SELECT id, 'mfa_required', 'false'
FROM control.tenant
WHERE deleted_at IS NULL
ON CONFLICT (tenant_id, param_key) DO NOTHING;
