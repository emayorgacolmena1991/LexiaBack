-- Catálogo de etapas del estatus del proceso, por tenant (bufete). El historial ya existe:
-- app.estatus_proceso (V115) guarda el código de etapa, el texto final, el autor y la fecha.
-- V116 es de coactivas (otra rama); esta es la siguiente libre.
--
-- Las etiquetas son una propuesta (tools/plantillas/ETAPAS_PROPUESTAS.md) pendiente de la abogada:
-- se corrigen editando esta tabla, sin tocar código. etiqueta_matriz es el texto tal como aparece en
-- la columna «ETAPA» de la matriz de la entidad; NULL = usar el nombre visible.
-- texto_sugerido admite {fecha} (hoy, dd/MM/yyyy), {notaria} (notaría de la remisión) y {numero_instancia}.

CREATE TABLE app.estatus_etapa (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES control.tenant (id),
    codigo          VARCHAR(60) NOT NULL,
    nombre          VARCHAR(120) NOT NULL,
    etiqueta_matriz VARCHAR(120),
    orden           INT NOT NULL,
    texto_sugerido  TEXT NOT NULL DEFAULT '',
    activo          BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, codigo)
);

CREATE INDEX estatus_etapa_activas_idx ON app.estatus_etapa (tenant_id, activo, orden);

ALTER TABLE app.estatus_etapa ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS tenant_isolation ON app.estatus_etapa;

CREATE POLICY tenant_isolation ON app.estatus_etapa
    USING (tenant_id = app.current_tenant_id())
    WITH CHECK (tenant_id = app.current_tenant_id());

GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE app.estatus_etapa TO lexia_app;
GRANT SELECT ON TABLE app.estatus_etapa TO lexia_readonly;

-- Cruce de la matriz por número de instancia (fase 6) y aviso de número repetido: siempre por tenant.
CREATE INDEX IF NOT EXISTS legal_case_numero_instancia_idx
    ON app.legal_case (tenant_id, numero_instancia)
    WHERE numero_instancia IS NOT NULL AND deleted_at IS NULL;

-- Semilla por bufete. Idempotente: no pisa etapas que el tenant ya tenga (ni las editadas).
-- SECURITY DEFINER como app.seed_tenant_masters (V113): la llama el alta de tenant sin sesión de tenant.
CREATE OR REPLACE FUNCTION app.seed_estatus_etapas(p_tenant uuid)
RETURNS integer
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = app, control, pg_temp
AS $$
DECLARE
    v_rows integer := 0;
BEGIN
    IF p_tenant IS NULL OR NOT EXISTS (
        SELECT 1 FROM control.tenant
        WHERE id = p_tenant AND deleted_at IS NULL AND tenant_kind = 'LEGAL_FIRM'
    ) THEN
        RETURN 0;
    END IF;

    INSERT INTO app.estatus_etapa (tenant_id, codigo, nombre, etiqueta_matriz, orden, texto_sugerido)
    SELECT p_tenant, e.codigo, e.nombre, e.etiqueta_matriz, e.orden, e.texto_sugerido
    FROM (VALUES
        ('ASIGNACION', 'Asignación', NULL, 1,
         'El expediente quedó asignado el {fecha}.'),
        ('CITA_CLIENTE', 'Confirmación cita con el cliente', 'Confirmación cita con el cliente', 2,
         'Pendiente confirmar la cita de firma con el cliente.'),
        ('REGULARIZAR_DOCUMENTACION', 'Regularizar documentación legal', 'Regularizar documentación legal', 3,
         'Pendiente regularizar la minuta y la documentación legal.'),
        ('FIRMA_BIESS', 'Firma BIESS, matriz y mutuo', 'Firma BIESS, matriz y mutuo', 4,
         'Pendiente la firma de la matriz y del mutuo.'),
        ('ENTREGA_NOTARIA', 'Entrega Escritura/Mutuo firmados a Notaría', 'Entrega Escritura/Mutuo firmados a Notaría', 5,
         'Escritura y mutuo enviados a {notaria} el {fecha}. Pendiente la liquidación de impuestos.'),
        ('RETIRO_LIQUIDACION', 'Retiro Liquidación Impuestos, Escritura y Mutuo', 'Retiro Liquidación Impuestos, Escritura y Mutuo', 6,
         'Pendiente el retiro de la liquidación, la escritura y el mutuo para la firma.'),
        ('PAGO_LIQUIDACION', 'Pago de liquidación e impuestos', NULL, 7,
         'Pendiente el pago de la liquidación y de los impuestos.'),
        ('ENTREGA_RETIRO', 'Entrega / retiro', NULL, 8,
         'Pendiente la entrega o el retiro de la escritura.'),
        ('REGULARIZACION_REGISTRO', 'Regularización Documentos para Registro de la Propiedad', 'Regularización Documentos para Registro de la Prop', 9,
         'Pendiente la firma y el envío de los documentos para el Registro de la Propiedad.'),
        ('INGRESO_REGISTRO', 'Ingreso de escrituras Registro de la Propiedad', 'Ingreso de escrituras Registro de la Propiedad', 10,
         'Las escrituras ingresaron al Registro de la Propiedad el {fecha}.'),
        ('PAGO_INGRESO_REGISTRO', 'Pago de ingreso al Registro', NULL, 11,
         'Pendiente el pago de ingreso al Registro de la Propiedad.'),
        ('VALIDAR_ENTREGA_REGISTRO', 'Validar Fecha Entrega Revision Reg. Propiedad', 'Validar Fecha Entrega Revision Reg. Propiedad', 12,
         'Pendiente validar la fecha de entrega de la revisión en el Registro de la Propiedad.'),
        ('RESULTADO_REGISTRO', 'Resultado Registro de la Propiedad', 'Resultado Registro de la Propiedad', 13,
         'Pendiente el resultado de la inscripción en el Registro de la Propiedad.'),
        ('CIERRE', 'Cierre', NULL, 14,
         'El trámite quedó cerrado el {fecha}.')
    ) AS e (codigo, nombre, etiqueta_matriz, orden, texto_sugerido)
    WHERE NOT EXISTS (
        SELECT 1 FROM app.estatus_etapa x WHERE x.tenant_id = p_tenant AND x.codigo = e.codigo
    );
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    RETURN v_rows;
END;
$$;

REVOKE ALL ON FUNCTION app.seed_estatus_etapas(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app.seed_estatus_etapas(uuid) TO lexia_app;

SELECT app.seed_estatus_etapas(id)
FROM control.tenant
WHERE deleted_at IS NULL
  AND tenant_kind = 'LEGAL_FIRM';
