-- V120 creó case_cycle_snapshot sin RLS. Mismo aislamiento por tenant que el resto de app.*.
ALTER TABLE app.case_cycle_snapshot ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON app.case_cycle_snapshot;
CREATE POLICY tenant_isolation ON app.case_cycle_snapshot
    USING (tenant_id = app.current_tenant_id())
    WITH CHECK (tenant_id = app.current_tenant_id());
