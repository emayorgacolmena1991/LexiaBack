CREATE TABLE app.ingesta_override (
    id              UUID PRIMARY KEY,
    tenant_id       UUID NOT NULL,
    lote_id         UUID NOT NULL,
    expediente_id   UUID NOT NULL,
    item_id         UUID NOT NULL,
    justificacion   TEXT NOT NULL,
    aprobado_por    UUID NOT NULL,
    aprobado_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    semaforo_antes  VARCHAR(16) NOT NULL,
    observacion_antes TEXT
);

CREATE INDEX idx_ingesta_override_expediente
  ON app.ingesta_override (tenant_id, expediente_id);

CREATE INDEX idx_ingesta_override_lote
  ON app.ingesta_override (tenant_id, lote_id, aprobado_at DESC);

GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE app.ingesta_override TO lexia_app;
GRANT SELECT ON TABLE app.ingesta_override TO lexia_readonly;

ALTER TABLE app.ingesta_override ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON app.ingesta_override
    USING (tenant_id = app.current_tenant_id())
    WITH CHECK (tenant_id = app.current_tenant_id());
