-- Aprovisionamiento de tenants: lexia_app solo tenía SELECT en control (V6).
GRANT INSERT, UPDATE ON control.tenant TO lexia_app;
GRANT INSERT, UPDATE, DELETE ON control.tenant_module TO lexia_app;
