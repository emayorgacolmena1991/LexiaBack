-- Coactivas Banecuador — Fase 1 (núcleo): oficinas, delegados, actas, expedientes, participantes,
-- notificaciones, historial de etapa y archivos. Reemplaza las etapas genéricas C1–C9 del proceso ECD
-- (se desactivan, no se borran) por las etapas procesales reales.
-- Las tablas de V5 (obligation, mandamiento, enforcement_measure, collection_tracking) quedan sin uso.

COMMENT ON TABLE app.obligation IS 'DEPRECATED: usar app.coactiva_* (V50).';
COMMENT ON TABLE app.mandamiento IS 'DEPRECATED: usar app.coactiva_* (V50).';
COMMENT ON TABLE app.enforcement_measure IS 'DEPRECATED: usar app.coactiva_* (V50).';
COMMENT ON TABLE app.collection_tracking IS 'DEPRECATED: usar app.coactiva_* (V50).';

-- ---------------------------------------------------------------------------
-- 1. Oficinas / sucursales de origen
-- ---------------------------------------------------------------------------
CREATE TABLE app.coactiva_oficina (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES control.tenant (id),
    codigo          VARCHAR(32) NOT NULL,
    nombre          VARCHAR(120) NOT NULL,
    provincia       VARCHAR(80),
    activo          BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order      INTEGER NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id, tenant_id),
    UNIQUE (tenant_id, codigo)
);

INSERT INTO app.coactiva_oficina (tenant_id, codigo, nombre, provincia, sort_order)
SELECT t.id, v.codigo, v.nombre, v.provincia, v.sort_order
FROM control.tenant t
CROSS JOIN (
    VALUES
        ('PORTOVIEJO', 'Portoviejo', 'Manabí', 1),
        ('MANTA', 'Manta', 'Manabí', 2),
        ('CHONE', 'Chone', 'Manabí', 3),
        ('PEDERNALES', 'Pedernales', 'Manabí', 4),
        ('GUAYAQUIL', 'Guayaquil', 'Guayas', 5),
        ('DAULE', 'Daule', 'Guayas', 6)
) AS v(codigo, nombre, provincia, sort_order)
WHERE t.deleted_at IS NULL
ON CONFLICT (tenant_id, codigo) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 2. Delegados (jueces coactivos) y su asignación por oficina
-- ---------------------------------------------------------------------------
CREATE TABLE app.coactiva_delegado (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES control.tenant (id),
    nombre              VARCHAR(200) NOT NULL,
    identificacion      VARCHAR(20),
    cargo               VARCHAR(160),
    resolucion_numero   VARCHAR(120),
    resolucion_fecha    DATE,
    vigente_desde       DATE,
    vigente_hasta       DATE,
    email               VARCHAR(200),
    activo              BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by          UUID,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by          UUID,
    deleted_at          TIMESTAMPTZ,
    row_version         BIGINT NOT NULL DEFAULT 0,
    UNIQUE (id, tenant_id)
);

CREATE TABLE app.coactiva_delegado_oficina (
    tenant_id       UUID NOT NULL REFERENCES control.tenant (id),
    delegado_id     UUID NOT NULL,
    oficina_codigo  VARCHAR(32) NOT NULL,
    PRIMARY KEY (delegado_id, oficina_codigo),
    FOREIGN KEY (delegado_id, tenant_id) REFERENCES app.coactiva_delegado (id, tenant_id)
);

CREATE INDEX ix_coactiva_delegado_oficina ON app.coactiva_delegado_oficina (tenant_id, oficina_codigo);

-- ---------------------------------------------------------------------------
-- 3. Archivos (almacenamiento en disco, ruta en storage_path)
-- ---------------------------------------------------------------------------
CREATE TABLE app.coactiva_archivo (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               UUID NOT NULL REFERENCES control.tenant (id),
    expediente_id           UUID,
    acta_id                 UUID,
    tipo                    VARCHAR(32) NOT NULL DEFAULT 'EXPEDIENTE_ESCANEADO'
        CHECK (tipo IN ('EXPEDIENTE_ESCANEADO', 'ACTA', 'LIQUIDACION', 'PROVIDENCIA', 'OFICIO',
                        'RESPUESTA_ENTIDAD', 'ESCRITO', 'EVIDENCIA', 'OTRO')),
    nombre_original         VARCHAR(400) NOT NULL,
    mime_type               VARCHAR(128),
    tamano_bytes            BIGINT NOT NULL DEFAULT 0,
    sha256                  VARCHAR(64),
    storage_path            VARCHAR(1024) NOT NULL,
    nro_juicio_detectado    VARCHAR(40),
    estado_vinculo          VARCHAR(16) NOT NULL DEFAULT 'VINCULADO'
        CHECK (estado_vinculo IN ('VINCULADO', 'SIN_ASIGNAR')),
    subido_por              UUID,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at              TIMESTAMPTZ,
    UNIQUE (id, tenant_id)
);

CREATE INDEX ix_coactiva_archivo_expediente ON app.coactiva_archivo (tenant_id, expediente_id)
    WHERE deleted_at IS NULL;
CREATE INDEX ix_coactiva_archivo_sin_asignar ON app.coactiva_archivo (tenant_id, created_at)
    WHERE estado_vinculo = 'SIN_ASIGNAR' AND deleted_at IS NULL;

-- ---------------------------------------------------------------------------
-- 4. Actas de entrega / devolución
-- ---------------------------------------------------------------------------
CREATE TABLE app.coactiva_acta (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES control.tenant (id),
    tipo                VARCHAR(16) NOT NULL DEFAULT 'ENTREGA' CHECK (tipo IN ('ENTREGA', 'DEVOLUCION')),
    titulo              VARCHAR(240),
    oficina_codigo      VARCHAR(32),
    fecha_acta          DATE,
    fecha_recepcion     DATE,
    entregado_por       VARCHAR(200),
    recibido_por        VARCHAR(200),
    archivo_id          UUID,
    total_items         INTEGER NOT NULL DEFAULT 0,
    estado              VARCHAR(16) NOT NULL DEFAULT 'BORRADOR' CHECK (estado IN ('BORRADOR', 'CONFIRMADA')),
    fuente              VARCHAR(16) NOT NULL DEFAULT 'MANUAL' CHECK (fuente IN ('MANUAL', 'EXCEL', 'PDF_OCR')),
    observaciones       TEXT,
    confirmada_at       TIMESTAMPTZ,
    confirmada_por      UUID,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by          UUID,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at          TIMESTAMPTZ,
    row_version         BIGINT NOT NULL DEFAULT 0,
    UNIQUE (id, tenant_id),
    FOREIGN KEY (archivo_id, tenant_id) REFERENCES app.coactiva_archivo (id, tenant_id)
);

ALTER TABLE app.coactiva_archivo
    ADD CONSTRAINT fk_coactiva_archivo_acta
        FOREIGN KEY (acta_id, tenant_id) REFERENCES app.coactiva_acta (id, tenant_id);

-- ---------------------------------------------------------------------------
-- 5. Expediente coactivo (1:1 con legal_case)
-- ---------------------------------------------------------------------------
CREATE TABLE app.coactiva_expediente (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               UUID NOT NULL REFERENCES control.tenant (id),
    case_id                 UUID NOT NULL,
    nro_juicio              VARCHAR(40) NOT NULL,
    nro_operacion           VARCHAR(40),
    anio                    INTEGER,
    oficina_codigo          VARCHAR(32),
    delegado_id             UUID,
    sae_user_id             UUID,
    asistente_user_id       UUID,
    fojas                   INTEGER,
    estado_operativo        VARCHAR(16) NOT NULL DEFAULT 'RECIBIDO'
        CHECK (estado_operativo IN ('RECIBIDO', 'DIGITALIZADO', 'EN_REVISION', 'VERIFICADO', 'ARCHIVADO')),
    etapa_reportada         VARCHAR(24),
    etapa_reportada_texto   VARCHAR(160),
    etapa_verificada        VARCHAR(24),
    etapa_sugerida_ia       VARCHAR(24),
    etapa_confirmada_por    UUID,
    etapa_confirmada_at     TIMESTAMPTZ,
    semaforo                VARCHAR(8) NOT NULL DEFAULT 'GRIS'
        CHECK (semaforo IN ('ROJO', 'AMARILLO', 'VERDE', 'GRIS')),
    semaforo_motivo         VARCHAR(400),
    fecha_citacion_opi      DATE,
    monto_original          NUMERIC(14, 2),
    convenio_usado          BOOLEAN NOT NULL DEFAULT FALSE,
    suspendido              BOOLEAN NOT NULL DEFAULT FALSE,
    suspension_motivo       VARCHAR(400),
    acta_entrega_id         UUID,
    fecha_ultima_actuacion  DATE,
    observaciones           TEXT,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by              UUID,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by              UUID,
    deleted_at              TIMESTAMPTZ,
    row_version             BIGINT NOT NULL DEFAULT 0,
    UNIQUE (id, tenant_id),
    UNIQUE (case_id),
    FOREIGN KEY (case_id, tenant_id) REFERENCES app.legal_case (id, tenant_id),
    FOREIGN KEY (delegado_id, tenant_id) REFERENCES app.coactiva_delegado (id, tenant_id),
    FOREIGN KEY (acta_entrega_id, tenant_id) REFERENCES app.coactiva_acta (id, tenant_id)
);

CREATE UNIQUE INDEX ux_coactiva_expediente_juicio
    ON app.coactiva_expediente (tenant_id, nro_juicio) WHERE deleted_at IS NULL;
CREATE INDEX ix_coactiva_expediente_operacion ON app.coactiva_expediente (tenant_id, nro_operacion);
CREATE INDEX ix_coactiva_expediente_etapa ON app.coactiva_expediente (tenant_id, etapa_verificada);
CREATE INDEX ix_coactiva_expediente_oficina ON app.coactiva_expediente (tenant_id, oficina_codigo);
CREATE INDEX ix_coactiva_expediente_semaforo ON app.coactiva_expediente (tenant_id, semaforo);

ALTER TABLE app.coactiva_archivo
    ADD CONSTRAINT fk_coactiva_archivo_expediente
        FOREIGN KEY (expediente_id, tenant_id) REFERENCES app.coactiva_expediente (id, tenant_id);

CREATE TABLE app.coactiva_acta_item (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES control.tenant (id),
    acta_id             UUID NOT NULL,
    fila                INTEGER NOT NULL DEFAULT 0,
    oficina_codigo      VARCHAR(32),
    nro_operacion       VARCHAR(40),
    nro_juicio          VARCHAR(40),
    anio                INTEGER,
    deudor_nombre       VARCHAR(240),
    deudor_cedula       VARCHAR(20),
    etapa_reportada     VARCHAR(160),
    fojas               INTEGER,
    expediente_id       UUID,
    estado_match        VARCHAR(16) NOT NULL DEFAULT 'PENDIENTE'
        CHECK (estado_match IN ('PENDIENTE', 'VINCULADO', 'DUPLICADO', 'ERROR', 'OMITIDO')),
    errores             TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id, tenant_id),
    FOREIGN KEY (acta_id, tenant_id) REFERENCES app.coactiva_acta (id, tenant_id),
    FOREIGN KEY (expediente_id, tenant_id) REFERENCES app.coactiva_expediente (id, tenant_id)
);

CREATE INDEX ix_coactiva_acta_item_acta ON app.coactiva_acta_item (tenant_id, acta_id, fila);

-- ---------------------------------------------------------------------------
-- 6. Participantes (deudor, codeudor, garantes)
-- ---------------------------------------------------------------------------
CREATE TABLE app.coactiva_participante (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES control.tenant (id),
    expediente_id       UUID NOT NULL,
    rol                 VARCHAR(16) NOT NULL CHECK (rol IN ('DEUDOR', 'CODEUDOR', 'GARANTE')),
    orden               INTEGER NOT NULL DEFAULT 1,
    tipo_persona        VARCHAR(16) NOT NULL DEFAULT 'NATURAL' CHECK (tipo_persona IN ('NATURAL', 'JURIDICA')),
    tipo_identificacion VARCHAR(16) NOT NULL DEFAULT 'CEDULA'
        CHECK (tipo_identificacion IN ('CEDULA', 'RUC', 'PASAPORTE')),
    identificacion      VARCHAR(20),
    nombre_completo     VARCHAR(240) NOT NULL,
    emails              TEXT,
    direccion           VARCHAR(400),
    telefono            VARCHAR(40),
    fuente              VARCHAR(16) NOT NULL DEFAULT 'MANUAL' CHECK (fuente IN ('ACTA', 'IA', 'MANUAL')),
    confianza_ia        NUMERIC(5, 2),
    verificado          BOOLEAN NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at          TIMESTAMPTZ,
    UNIQUE (id, tenant_id),
    FOREIGN KEY (expediente_id, tenant_id) REFERENCES app.coactiva_expediente (id, tenant_id)
);

CREATE INDEX ix_coactiva_participante_expediente ON app.coactiva_participante (tenant_id, expediente_id)
    WHERE deleted_at IS NULL;
CREATE INDEX ix_coactiva_participante_identificacion ON app.coactiva_participante (tenant_id, identificacion);

-- ---------------------------------------------------------------------------
-- 7. Notificaciones (razones) por participante
-- ---------------------------------------------------------------------------
CREATE TABLE app.coactiva_notificacion (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES control.tenant (id),
    expediente_id       UUID NOT NULL,
    participante_id     UUID,
    acto                VARCHAR(16) NOT NULL CHECK (acto IN ('RPV', 'OPI', 'PROVIDENCIA')),
    medio               VARCHAR(16) NOT NULL CHECK (medio IN ('BOLETA', 'PERSONAL', 'CORREO')),
    numero_boleta       INTEGER,
    fecha               DATE,
    archivo_id          UUID,
    pagina_desde        INTEGER,
    pagina_hasta        INTEGER,
    valida              BOOLEAN NOT NULL DEFAULT TRUE,
    fuente              VARCHAR(16) NOT NULL DEFAULT 'MANUAL' CHECK (fuente IN ('IA', 'MANUAL')),
    observacion         VARCHAR(400),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by          UUID,
    deleted_at          TIMESTAMPTZ,
    UNIQUE (id, tenant_id),
    FOREIGN KEY (expediente_id, tenant_id) REFERENCES app.coactiva_expediente (id, tenant_id),
    FOREIGN KEY (participante_id, tenant_id) REFERENCES app.coactiva_participante (id, tenant_id),
    FOREIGN KEY (archivo_id, tenant_id) REFERENCES app.coactiva_archivo (id, tenant_id)
);

CREATE INDEX ix_coactiva_notificacion_expediente ON app.coactiva_notificacion (tenant_id, expediente_id)
    WHERE deleted_at IS NULL;

-- ---------------------------------------------------------------------------
-- 8. Historial de etapa y eventos del expediente (timeline)
-- ---------------------------------------------------------------------------
CREATE TABLE app.coactiva_etapa_historial (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES control.tenant (id),
    expediente_id       UUID NOT NULL,
    etapa_anterior      VARCHAR(24),
    etapa_nueva         VARCHAR(24) NOT NULL,
    motivo              VARCHAR(600),
    usuario_id          UUID,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id, tenant_id),
    FOREIGN KEY (expediente_id, tenant_id) REFERENCES app.coactiva_expediente (id, tenant_id)
);

CREATE INDEX ix_coactiva_etapa_historial ON app.coactiva_etapa_historial (tenant_id, expediente_id, created_at);

CREATE TABLE app.coactiva_evento (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES control.tenant (id),
    expediente_id       UUID NOT NULL,
    tipo                VARCHAR(40) NOT NULL,
    titulo              VARCHAR(240) NOT NULL,
    detalle             TEXT,
    usuario_id          UUID,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id, tenant_id),
    FOREIGN KEY (expediente_id, tenant_id) REFERENCES app.coactiva_expediente (id, tenant_id)
);

CREATE INDEX ix_coactiva_evento ON app.coactiva_evento (tenant_id, expediente_id, created_at);

-- ---------------------------------------------------------------------------
-- 9. RLS + grants
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    tbl TEXT;
BEGIN
    FOREACH tbl IN ARRAY ARRAY[
        'app.coactiva_oficina', 'app.coactiva_delegado', 'app.coactiva_delegado_oficina',
        'app.coactiva_archivo', 'app.coactiva_acta', 'app.coactiva_acta_item',
        'app.coactiva_expediente', 'app.coactiva_participante', 'app.coactiva_notificacion',
        'app.coactiva_etapa_historial', 'app.coactiva_evento'
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

-- ---------------------------------------------------------------------------
-- 10. Etapas procesales reales del proceso ECD (C1–C9 quedan inactivas)
-- ---------------------------------------------------------------------------
UPDATE app.process_stage_def s
SET active = FALSE,
    deprecated_at = COALESCE(s.deprecated_at, now())
FROM app.process_definition pd
WHERE s.process_definition_id = pd.id
  AND pd.case_type = 'ECD'
  AND s.code IN ('c1', 'c2', 'c3', 'c4', 'c5', 'c6', 'c7', 'c8', 'c9');

INSERT INTO app.process_stage_def
    (tenant_id, process_definition_id, code, label, short_label, sort_order, active, owner_actor, color_key)
SELECT pd.tenant_id, pd.id, v.code, v.label, v.short_label, v.sort_order, TRUE, 'ABOGADO', v.color
FROM app.process_definition pd
CROSS JOIN (
    VALUES
        ('previa', 'Documentación previa', 'Previa', 10, 'blue'),
        ('rpv', 'Requerimiento de pago voluntario', 'RPV', 11, 'blue'),
        ('opi_emitida', 'OPI emitida sin notificar', 'OPI emitida', 12, 'orange'),
        ('notif_coa', 'Notificación COA (OPI notificada)', 'Notificación COA', 13, 'blue'),
        ('medidas', 'Medidas cautelares', 'Medidas', 14, 'purple'),
        ('embargo', 'Embargo de bienes', 'Embargo', 15, 'purple'),
        ('avaluo', 'Avalúo', 'Avalúo', 16, 'purple'),
        ('remate', 'Remate', 'Remate', 17, 'purple'),
        ('convenio', 'Convenio de pago vigente', 'Convenio', 18, 'green'),
        ('archivado', 'Archivo del proceso', 'Archivado', 19, 'green')
) AS v(code, label, short_label, sort_order, color)
WHERE pd.case_type = 'ECD'
  AND pd.deleted_at IS NULL
ON CONFLICT (process_definition_id, code) DO NOTHING;

DELETE FROM app.process_stage_transition t
USING app.process_definition pd
WHERE t.process_definition_id = pd.id
  AND pd.case_type = 'ECD'
  AND t.from_stage_code IN ('c1', 'c2', 'c3', 'c4', 'c5', 'c6', 'c7', 'c8', 'c9');

INSERT INTO app.process_stage_transition (tenant_id, process_definition_id, from_stage_code, to_stage_code)
SELECT pd.tenant_id, pd.id, v.from_code, v.to_code
FROM app.process_definition pd
CROSS JOIN (
    VALUES
        ('previa', 'rpv'),
        ('rpv', 'opi_emitida'),
        ('opi_emitida', 'notif_coa'),
        ('notif_coa', 'medidas'),
        ('medidas', 'embargo'),
        ('medidas', 'convenio'),
        ('medidas', 'archivado'),
        ('embargo', 'avaluo'),
        ('avaluo', 'remate'),
        ('remate', 'archivado'),
        ('convenio', 'medidas'),
        ('convenio', 'archivado'),
        ('notif_coa', 'convenio'),
        ('opi_emitida', 'convenio')
) AS v(from_code, to_code)
WHERE pd.case_type = 'ECD'
  AND pd.deleted_at IS NULL
ON CONFLICT (tenant_id, process_definition_id, from_stage_code, to_stage_code) DO NOTHING;

UPDATE app.process_definition
SET name = 'Coactivas Banecuador (etapas procesales)'
WHERE case_type = 'ECD' AND deleted_at IS NULL;

-- ---------------------------------------------------------------------------
-- 11. Permisos del módulo
-- ---------------------------------------------------------------------------
INSERT INTO app.permission (id, code, name, module_code) VALUES
    (gen_random_uuid(), 'coactivas:expediente:leer', 'Ver expedientes coactivos', 'coactivas'),
    (gen_random_uuid(), 'coactivas:expediente:escribir', 'Gestionar expedientes coactivos', 'coactivas'),
    (gen_random_uuid(), 'coactivas:diagnostico:confirmar', 'Confirmar diagnóstico y etapa procesal', 'coactivas'),
    (gen_random_uuid(), 'coactivas:documento:generar', 'Generar providencias y oficios', 'coactivas'),
    (gen_random_uuid(), 'coactivas:documento:firmar', 'Registrar firma de providencias', 'coactivas'),
    (gen_random_uuid(), 'coactivas:financiero:gestionar', 'Gestionar honorarios y convenios', 'coactivas'),
    (gen_random_uuid(), 'coactivas:reportes:leer', 'Ver reportes de coactivas', 'coactivas'),
    (gen_random_uuid(), 'admin:coactivas:configurar', 'Configurar módulo de coactivas', 'admin')
ON CONFLICT (code) DO NOTHING;

INSERT INTO app.role_permission (role_id, permission_id, tenant_id)
SELECT r.id, p.id, r.tenant_id
FROM app.role r
JOIN app.permission p ON p.code IN (
    'coactivas:expediente:leer', 'coactivas:expediente:escribir', 'coactivas:diagnostico:confirmar',
    'coactivas:documento:generar', 'coactivas:documento:firmar', 'coactivas:financiero:gestionar',
    'coactivas:reportes:leer', 'admin:coactivas:configurar')
WHERE r.code IN ('ADMIN', 'ABOGADO_SENIOR')
ON CONFLICT DO NOTHING;

INSERT INTO app.role_permission (role_id, permission_id, tenant_id)
SELECT r.id, p.id, r.tenant_id
FROM app.role r
JOIN app.permission p ON p.code IN (
    'coactivas:expediente:leer', 'coactivas:expediente:escribir', 'coactivas:documento:generar',
    'coactivas:reportes:leer')
WHERE r.code = 'ANALISTA'
ON CONFLICT DO NOTHING;

INSERT INTO app.role_permission (role_id, permission_id, tenant_id)
SELECT r.id, p.id, r.tenant_id
FROM app.role r
JOIN app.permission p ON p.code IN ('coactivas:expediente:leer', 'coactivas:reportes:leer')
WHERE r.code = 'SOLO_LECTURA'
ON CONFLICT DO NOTHING;
