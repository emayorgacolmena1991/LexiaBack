CREATE TABLE app.coactiva_tarea_sugerida (
    id              UUID PRIMARY KEY,
    tenant_id       UUID NOT NULL,
    expediente_id   UUID NOT NULL,
    analisis_id     UUID,
    titulo          VARCHAR(240) NOT NULL,
    descripcion     TEXT,
    prioridad       VARCHAR(16) NOT NULL,
    estado          VARCHAR(16) NOT NULL
        CHECK (estado IN ('SUGERIDA', 'INCORPORADA', 'DESCARTADA')),
    origen_ia       BOOLEAN NOT NULL DEFAULT TRUE,
    creado_en       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id, tenant_id),
    FOREIGN KEY (expediente_id, tenant_id) REFERENCES app.coactiva_expediente (id, tenant_id),
    FOREIGN KEY (analisis_id) REFERENCES app.coactiva_analisis (id)
);

CREATE INDEX ix_coactiva_tarea_sugerida_expediente
    ON app.coactiva_tarea_sugerida (tenant_id, expediente_id, creado_en);

ALTER TABLE app.coactiva_tarea_sugerida ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON app.coactiva_tarea_sugerida;
CREATE POLICY tenant_isolation ON app.coactiva_tarea_sugerida
    USING (tenant_id = app.current_tenant_id())
    WITH CHECK (tenant_id = app.current_tenant_id());
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE app.coactiva_tarea_sugerida TO lexia_app;
GRANT SELECT ON TABLE app.coactiva_tarea_sugerida TO lexia_readonly;
