-- Snapshot versionado de cada envío bufete → notaría. Vive en control, junto a la remisión.
-- minuta_draft_id y contrato_draft_id sin FK: app.minuta_draft exige (id, tenant_id).
CREATE TABLE control.notary_remision_version (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    remision_id             UUID NOT NULL REFERENCES control.notary_remision (id) ON DELETE CASCADE,
    version_no              INTEGER NOT NULL CHECK (version_no >= 1),
    payload                 JSONB NOT NULL,
    minuta_draft_id         UUID,
    minuta_storage_ref      VARCHAR(1024),
    minuta_file_name        VARCHAR(500),
    contrato_draft_id       UUID,
    contrato_storage_ref    VARCHAR(1024),
    contrato_file_name      VARCHAR(500),
    observaciones_bufete    TEXT,
    created_by              UUID REFERENCES app.app_user (id),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ux_notary_remision_version UNIQUE (remision_id, version_no)
);

COMMENT ON TABLE control.notary_remision_version IS
    'Versión inmutable de lo enviado a notaría: JSON y copia propia de los .docx.';

-- Remisiones anteriores: sin minuta copiada ni JSON que reconstruir.
INSERT INTO control.notary_remision_version (
    remision_id, version_no, payload, observaciones_bufete, created_by, created_at
)
SELECT id, 1, '{}'::jsonb, observaciones_bufete, emitido_por_user_id, emitido_en
FROM control.notary_remision;

ALTER TABLE control.notary_remision_document
    ADD COLUMN version_id UUID REFERENCES control.notary_remision_version (id);

UPDATE control.notary_remision_document d
SET version_id = v.id
FROM control.notary_remision_version v
WHERE v.remision_id = d.remision_id
  AND v.version_no = 1;

ALTER TABLE control.notary_remision_document
    ALTER COLUMN version_id SET NOT NULL;

CREATE INDEX ix_notary_remision_doc_version
    ON control.notary_remision_document (version_id, sort_order);

-- Las tablas de control solo reciben SELECT por defecto (ver V93). Sin UPDATE ni DELETE: inmutable.
GRANT SELECT, INSERT ON TABLE control.notary_remision_version TO lexia_app;
GRANT SELECT ON TABLE control.notary_remision_version TO lexia_readonly;
