-- Observación del estudio de títulos ligada al documento del expediente que la origina.
-- NULL = observación general (del expediente o de varios documentos).
-- La tabla del LegalDocument es app.document. La FK compuesta con tenant_id impide apuntar a un
-- documento de otro tenant; el RLS de app.title_observation (V6) cubre la columna nueva.
ALTER TABLE app.title_observation
    ADD COLUMN legal_document_id UUID;

ALTER TABLE app.title_observation
    ADD CONSTRAINT fk_title_observation_document
        FOREIGN KEY (legal_document_id, tenant_id) REFERENCES app.document (id, tenant_id);

CREATE INDEX ix_title_observation_document
    ON app.title_observation (legal_document_id)
    WHERE legal_document_id IS NOT NULL;
