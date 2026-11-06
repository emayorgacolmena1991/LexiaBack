-- V79 creó tablas notarial en control con solo SELECT (default V1).
-- lexia_app necesita escribir remisiones y vínculos.
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE control.notary_remision TO lexia_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE control.notary_remision_document TO lexia_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE control.tenant_notary_partnership TO lexia_app;
GRANT SELECT, INSERT, UPDATE ON TABLE control.notary_tenant_profile TO lexia_app;

GRANT SELECT ON TABLE control.notary_remision TO lexia_readonly;
GRANT SELECT ON TABLE control.notary_remision_document TO lexia_readonly;
GRANT SELECT ON TABLE control.tenant_notary_partnership TO lexia_readonly;
GRANT SELECT ON TABLE control.notary_tenant_profile TO lexia_readonly;
