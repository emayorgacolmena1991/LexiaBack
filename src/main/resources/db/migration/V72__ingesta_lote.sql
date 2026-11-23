CREATE TABLE app.ingesta_lote (
    id                  UUID PRIMARY KEY,
    tenant_id           UUID NOT NULL,
    estado              VARCHAR(32) NOT NULL,
    total_filas         INT NOT NULL,
    filas_completadas   INT NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id, tenant_id)
);

CREATE TABLE app.ingesta_item (
    id                    UUID PRIMARY KEY,
    tenant_id             UUID NOT NULL,
    lote_id               UUID NOT NULL,
    client_row_id         VARCHAR(80) NOT NULL,
    orden                 INT NOT NULL,
    expediente_id         UUID,
    borrador_id           UUID,
    estado                VARCHAR(32) NOT NULL,
    porcentaje_progreso   INT NOT NULL DEFAULT 0,
    error_log             TEXT,
    detalle               JSONB NOT NULL,
    FOREIGN KEY (lote_id, tenant_id) REFERENCES app.ingesta_lote (id, tenant_id),
    FOREIGN KEY (expediente_id) REFERENCES app.legal_case (id),
    FOREIGN KEY (borrador_id) REFERENCES app.expediente_borrador (id),
    UNIQUE (lote_id, client_row_id)
);

CREATE INDEX ix_ingesta_item_lote ON app.ingesta_item (tenant_id, lote_id, orden);

ALTER TABLE app.ingesta_lote ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON app.ingesta_lote;
CREATE POLICY tenant_isolation ON app.ingesta_lote
    USING (tenant_id = app.current_tenant_id())
    WITH CHECK (tenant_id = app.current_tenant_id());

ALTER TABLE app.ingesta_item ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON app.ingesta_item;
CREATE POLICY tenant_isolation ON app.ingesta_item
    USING (tenant_id = app.current_tenant_id())
    WITH CHECK (tenant_id = app.current_tenant_id());

GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE app.ingesta_lote TO lexia_app;
GRANT SELECT ON TABLE app.ingesta_lote TO lexia_readonly;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE app.ingesta_item TO lexia_app;
GRANT SELECT ON TABLE app.ingesta_item TO lexia_readonly;
