-- V91__notary_read_document_version.sql
-- PDF §"Extender, no reemplazar": la notaría lee documentos del bufete
-- sin adoptar tenant. Rama OR solo en app.document_version.
-- Políticas permisivas se combinan con OR: tenant_isolation sigue intacta.

DROP POLICY IF EXISTS notary_read_document_version ON app.document_version;

CREATE POLICY notary_read_document_version ON app.document_version
    FOR SELECT
    USING (
        tenant_id = app.current_tenant_id()
        OR EXISTS (
            SELECT 1
              FROM control.notary_remision_document nrd
              JOIN control.notary_remision nr
                ON nr.id = nrd.remision_id
             WHERE nrd.firm_document_version_id = app.document_version.id
               AND nrd.firm_tenant_id           = app.document_version.tenant_id
               AND nr.notary_tenant_id          = app.current_tenant_id()
        )
    );
