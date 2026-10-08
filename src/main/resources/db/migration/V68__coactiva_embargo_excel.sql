-- Data 2 (Excel de Embargo): un lote semanal compartido por tenant y una fila por expediente dentro del lote.

CREATE TABLE app.coactiva_embargo_lote (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES control.tenant (id),
    numero          INT NOT NULL,
    estado          VARCHAR(24) NOT NULL DEFAULT 'EN_PREPARACION'
        CHECK (estado IN ('EN_PREPARACION', 'ENTREGADO')),
    fecha_corte     TIMESTAMPTZ NOT NULL,
    entregado_at    TIMESTAMPTZ,
    entregado_por   UUID,
    created_by      UUID,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id, tenant_id)
);

CREATE UNIQUE INDEX ux_coactiva_embargo_lote_activo
    ON app.coactiva_embargo_lote (tenant_id)
    WHERE estado = 'EN_PREPARACION';

CREATE TABLE app.coactiva_embargo_registro (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                UUID NOT NULL REFERENCES control.tenant (id),
    lote_id                  UUID NOT NULL,
    expediente_id            UUID NOT NULL,
    juzgado                  VARCHAR(250),
    oficina_origen_credito   VARCHAR(250),
    operacion                VARCHAR(120),
    numero_juicio            VARCHAR(120),
    nombre_coactivado        VARCHAR(250),
    nombre_titular_operacion VARCHAR(250),
    valor_transferido        NUMERIC(14, 2),
    fecha_proceso            DATE,
    nombre_der_sac           VARCHAR(250),
    numero_oficio_respuesta  VARCHAR(120),
    numero_documento         VARCHAR(120),
    row_version              BIGINT NOT NULL DEFAULT 0,
    created_by               UUID,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by               UUID,
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id, tenant_id),
    UNIQUE (lote_id, expediente_id),
    FOREIGN KEY (lote_id, tenant_id) REFERENCES app.coactiva_embargo_lote (id, tenant_id),
    FOREIGN KEY (expediente_id, tenant_id) REFERENCES app.coactiva_expediente (id, tenant_id)
);

DO $$
DECLARE
    tbl TEXT;
BEGIN
    FOREACH tbl IN ARRAY ARRAY['app.coactiva_embargo_lote', 'app.coactiva_embargo_registro']
    LOOP
        EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY', tbl);
        EXECUTE format('DROP POLICY IF EXISTS tenant_isolation ON %s', tbl);
        EXECUTE format(
            'CREATE POLICY tenant_isolation ON %s
                USING (tenant_id = app.current_tenant_id())
                WITH CHECK (tenant_id = app.current_tenant_id())',
            tbl);
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE %s TO lexia_app', tbl);
        EXECUTE format('GRANT SELECT ON TABLE %s TO lexia_readonly', tbl);
    END LOOP;
END;
$$;
