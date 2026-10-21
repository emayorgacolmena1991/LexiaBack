-- Etapas de pantalla Escrito y Honorarios, archivo en el timeline y actuaciones.

UPDATE app.process_stage_def s
SET sort_order = v.sort_order
FROM app.process_definition pd,
     (VALUES
        ('embargo', 16),
        ('avaluo', 18),
        ('remate', 19),
        ('convenio', 20),
        ('archivado', 21)
     ) AS v(code, sort_order)
WHERE s.process_definition_id = pd.id
  AND pd.case_type = 'ECD'
  AND pd.deleted_at IS NULL
  AND s.code = v.code;

INSERT INTO app.process_stage_def
    (tenant_id, process_definition_id, code, label, short_label, sort_order, active, owner_actor, color_key)
SELECT pd.tenant_id, pd.id, v.code, v.label, v.short_label, v.sort_order, TRUE, 'ABOGADO', v.color
FROM app.process_definition pd
CROSS JOIN (
    VALUES
        ('escrito', 'Atención a escrito / levantamiento', 'Escrito', 15, 'orange'),
        ('honorarios', 'Aplicación y honorarios', 'Honorarios', 17, 'green')
) AS v(code, label, short_label, sort_order, color)
WHERE pd.case_type = 'ECD'
  AND pd.deleted_at IS NULL
ON CONFLICT (process_definition_id, code) DO NOTHING;

INSERT INTO app.process_stage_transition (tenant_id, process_definition_id, from_stage_code, to_stage_code)
SELECT pd.tenant_id, pd.id, v.from_code, v.to_code
FROM app.process_definition pd
CROSS JOIN (
    VALUES
        ('medidas', 'escrito'),
        ('escrito', 'medidas'),
        ('escrito', 'embargo'),
        ('escrito', 'convenio'),
        ('embargo', 'honorarios'),
        ('honorarios', 'avaluo'),
        ('honorarios', 'embargo'),
        ('honorarios', 'archivado'),
        ('convenio', 'escrito'),
        ('notif_coa', 'escrito')
) AS v(from_code, to_code)
WHERE pd.case_type = 'ECD'
  AND pd.deleted_at IS NULL
ON CONFLICT (tenant_id, process_definition_id, from_stage_code, to_stage_code) DO NOTHING;

INSERT INTO app.case_stage (tenant_id, case_id, stage_def_id, status)
SELECT lc.tenant_id, lc.id, sd.id, 'pending'
FROM app.legal_case lc
JOIN app.process_stage_def sd
  ON sd.process_definition_id = lc.process_definition_id
 AND sd.tenant_id = lc.tenant_id
 AND sd.code IN ('escrito', 'honorarios')
WHERE lc.case_type = 'ECD'
  AND lc.deleted_at IS NULL
ON CONFLICT (case_id, stage_def_id) DO NOTHING;

ALTER TABLE app.coactiva_archivo DROP CONSTRAINT coactiva_archivo_tipo_check;
ALTER TABLE app.coactiva_archivo
    ADD CONSTRAINT coactiva_archivo_tipo_check
        CHECK (tipo IN ('EXPEDIENTE_ESCANEADO', 'ACTA', 'LIQUIDACION', 'PROVIDENCIA', 'OFICIO',
                        'RESPUESTA_ENTIDAD', 'ESCRITO', 'EVIDENCIA', 'OTRO', 'ACTUACION'));

ALTER TABLE app.coactiva_evento
    ADD COLUMN archivo_id UUID;

ALTER TABLE app.coactiva_evento
    ADD CONSTRAINT fk_coactiva_evento_archivo
        FOREIGN KEY (archivo_id, tenant_id) REFERENCES app.coactiva_archivo (id, tenant_id);

CREATE TABLE app.coactiva_plantilla (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL REFERENCES control.tenant (id),
    codigo      VARCHAR(64) NOT NULL,
    nombre      VARCHAR(240) NOT NULL,
    etapa       VARCHAR(24) NOT NULL,
    activo      BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE (id, tenant_id),
    UNIQUE (tenant_id, codigo)
);

CREATE TABLE app.coactiva_actuacion (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID NOT NULL REFERENCES control.tenant (id),
    expediente_id    UUID NOT NULL,
    plantilla_id     UUID NOT NULL,
    archivo_id       UUID,
    estado           VARCHAR(24) NOT NULL,
    idempotency_key  VARCHAR(120),
    created_by       UUID,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id, tenant_id),
    UNIQUE (tenant_id, expediente_id, idempotency_key),
    FOREIGN KEY (expediente_id, tenant_id) REFERENCES app.coactiva_expediente (id, tenant_id),
    FOREIGN KEY (plantilla_id, tenant_id) REFERENCES app.coactiva_plantilla (id, tenant_id),
    FOREIGN KEY (archivo_id, tenant_id) REFERENCES app.coactiva_archivo (id, tenant_id)
);

CREATE TABLE app.coactiva_medida (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES control.tenant (id),
    expediente_id   UUID NOT NULL,
    tipo            VARCHAR(40) NOT NULL,
    descripcion     TEXT,
    fecha           DATE,
    estado          VARCHAR(24) NOT NULL DEFAULT 'VIGENTE',
    created_by      UUID,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id, tenant_id),
    FOREIGN KEY (expediente_id, tenant_id) REFERENCES app.coactiva_expediente (id, tenant_id)
);

CREATE TABLE app.coactiva_solicitud (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES control.tenant (id),
    expediente_id       UUID NOT NULL,
    operacion           VARCHAR(32) NOT NULL
        CHECK (operacion IN ('CARGA', 'TRASLADO', 'DATADOC', 'FIRMA')),
    detalle             TEXT,
    idempotency_key     VARCHAR(120),
    resultado_http      INT NOT NULL,
    resultado_code      VARCHAR(64) NOT NULL,
    resultado_message   TEXT NOT NULL,
    created_by          UUID,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id, tenant_id),
    UNIQUE (tenant_id, expediente_id, operacion, idempotency_key),
    FOREIGN KEY (expediente_id, tenant_id) REFERENCES app.coactiva_expediente (id, tenant_id)
);

INSERT INTO app.coactiva_plantilla (tenant_id, codigo, nombre, etapa)
SELECT t.id, v.codigo, v.nombre, v.etapa
FROM control.tenant t
CROSS JOIN (
    VALUES
        ('opi', 'FORMATO ORDEN DE PAGO INMEDIATO.docx', 'OPI_EMITIDA'),
        ('ratificacion-gye', 'FORMATO RATIFICACION DE MEDIDAS GYE.docx', 'MEDIDAS_CAUTELARES'),
        ('ratificacion-portoviejo', 'FORMATO RATIFICACION PORTOVIEJO.docx', 'MEDIDAS_CAUTELARES'),
        ('ratificacion-chone', 'FORMATO RATIFICACION CHONE - EL CARMEN.docx', 'MEDIDAS_CAUTELARES'),
        ('escrito-providencia', 'PROVIDENCIA ATENCION ESCRITO.docx', 'ESCRITO'),
        ('escrito-levantamiento', 'OFICIO LEVANTAMIENTO MEDIDA.docx', 'ESCRITO'),
        ('convenio-facilidad', 'FORMULARIO FACILIDAD DE PAGO.docx', 'CONVENIO'),
        ('convenio-traslado', 'PROVIDENCIA CORRER TRASLADO.docx', 'CONVENIO'),
        ('embargo-transferencia', 'FORMATO EMBARGO DE VALORES TRANSFERENCIA.docx', 'EMBARGO'),
        ('embargo-banco', 'FORMATO PROV EMBARGO BANCO GYE.docx', 'EMBARGO'),
        ('embargo-oficio', 'OFICIO DE EMBARGO TRANSFERENCIA.docx', 'EMBARGO'),
        ('embargo-cheque', 'OFICIO EMBARGO CHEQUE.docx', 'EMBARGO'),
        ('honorarios-solicitud', 'SOLICITUD CARGA HONORARIOS EMBARGO.docx', 'HONORARIOS'),
        ('honorarios-providencia', 'PROV. COBRO DE HONORARIOS POR EMBARGO.docx', 'HONORARIOS'),
        ('honorarios-informe', 'INFORME COBRO HONORARIOS POR EMBARGO.docx', 'HONORARIOS')
) AS v(codigo, nombre, etapa)
ON CONFLICT (tenant_id, codigo) DO NOTHING;

DO $$
DECLARE
    tbl TEXT;
BEGIN
    FOREACH tbl IN ARRAY ARRAY[
        'app.coactiva_plantilla', 'app.coactiva_actuacion', 'app.coactiva_medida', 'app.coactiva_solicitud'
    ]
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
