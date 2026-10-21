-- Expediente unificado: un PDF del juicio, diagnóstico por etapa (no checklist por archivo).

ALTER TABLE app.coactiva_archivo DROP CONSTRAINT coactiva_archivo_tipo_check;
ALTER TABLE app.coactiva_archivo
    ADD CONSTRAINT coactiva_archivo_tipo_check
        CHECK (tipo IN ('EXPEDIENTE_ESCANEADO', 'EXPEDIENTE_UNIFICADO', 'ACTA', 'LIQUIDACION',
                        'PROVIDENCIA', 'OFICIO', 'RESPUESTA_ENTIDAD', 'ESCRITO', 'EVIDENCIA',
                        'OTRO', 'ACTUACION'));

ALTER TABLE app.coactiva_expediente
    ADD COLUMN estado_analisis VARCHAR(16) NOT NULL DEFAULT 'NO_APLICA'
        CHECK (estado_analisis IN ('NO_APLICA', 'ANALIZANDO', 'ANALIZADO', 'ERROR_ANALISIS')),
    ADD COLUMN analisis_archivo_id UUID;

CREATE TABLE app.coactiva_analisis (
    id                      UUID PRIMARY KEY,
    tenant_id               UUID NOT NULL,
    expediente_id           UUID NOT NULL,
    archivo_id              UUID NOT NULL,
    prompt_id               BIGINT,
    etapa                   VARCHAR(32) NOT NULL,
    estado                  VARCHAR(16) NOT NULL CHECK (estado IN ('ANALIZADO', 'ERROR')),
    porcentaje_completitud  SMALLINT,
    etapa_detectada         VARCHAR(32),
    resultado               TEXT NOT NULL,
    error                   TEXT,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id, tenant_id),
    FOREIGN KEY (expediente_id, tenant_id) REFERENCES app.coactiva_expediente (id, tenant_id),
    FOREIGN KEY (archivo_id, tenant_id) REFERENCES app.coactiva_archivo (id, tenant_id)
);

CREATE INDEX ix_coactiva_analisis_expediente
    ON app.coactiva_analisis (tenant_id, expediente_id, created_at DESC);

ALTER TABLE app.coactiva_analisis ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON app.coactiva_analisis;
CREATE POLICY tenant_isolation ON app.coactiva_analisis
    USING (tenant_id = app.current_tenant_id())
    WITH CHECK (tenant_id = app.current_tenant_id());
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE app.coactiva_analisis TO lexia_app;
GRANT SELECT ON TABLE app.coactiva_analisis TO lexia_readonly;

-- Catálogo existente (tipo + etapa). Prompts del expediente único, una fila por etapa procesal.
INSERT INTO app.coactiva_prompt_catalog (tipo_documento, etapa, system_prompt, descripcion) VALUES
('EXPEDIENTE_UNIFICADO', '*', $p$
Eres auditor legal de BanEcuador en juicios coactivos. Recibes el OCR de UN solo PDF (expediente físico completo), con marcas --- FOJA n ---.
Contexto: etapa {{etapa}}, juicio {{nro_juicio}}, operación {{nro_operacion}}, deudor {{deudor}}.
Detecta la etapa real y lista documentos presentes y faltantes dentro del PDF. No inventes fojas ni documentos que no estén en el texto.
$p$, 'Diagnóstico genérico del expediente unificado'),

('EXPEDIENTE_UNIFICADO', 'PREVIA', $p$
Eres auditor legal de BanEcuador. Expediente único (PDF completo). Contexto: etapa {{etapa}}, juicio {{nro_juicio}}, operación {{nro_operacion}}, deudor {{deudor}}.
Etapa previa. Marca presente/ausente, con fojas:
- PAGARE (pagaré a la orden o título de crédito)
- AVISO_VENCIMIENTO
- RPV (requerimiento de pago voluntario)
- LIQUIDACION_PREVIA (orden de cobro / liquidación con número de operación)
Alerta si falta el pagaré o la liquidación previa. Sugiere emitir RPV u OPI según lo que falte.
$p$, 'Documentación previa'),

('EXPEDIENTE_UNIFICADO', 'RPV', $p$
Eres auditor legal de BanEcuador. Expediente único. Contexto: etapa {{etapa}}, juicio {{nro_juicio}}, operación {{nro_operacion}}, deudor {{deudor}}.
Verifica el Requerimiento de Pago Voluntario (término 10 días) y la citación: 1 notificación personal o 2 boletas por persona, o correo fijado.
Documentos: PAGARE, AVISO_VENCIMIENTO, RPV, RAZON_NOTIFICACION, LIQUIDACION_PREVIA.
Alerta roja si no hay citación válida. Si el RPV está notificado y venció el término, la etapa detectada puede avanzar a OPI.
$p$, 'Requerimiento de pago voluntario'),

('EXPEDIENTE_UNIFICADO', 'OPI_EMITIDA', $p$
Eres auditor legal de BanEcuador. Expediente único. Contexto: etapa {{etapa}}, juicio {{nro_juicio}}, operación {{nro_operacion}}, deudor {{deudor}}.
Orden de Pago Inmediato aún sin notificación válida. Busca:
- OPI (término 3 días)
- CEDULA_RUC de titular, codeudores y garantes
- DIRECCION_O_CORREO de citación
- RAZON_NOTIFICACION: 1 personal o 2 boletas por persona
- HONORARIOS_NOTIFICADOR
Si la OPI no está notificada, alerta crítica (notificación pendiente) y etapa_procesal_detectada OPI_EMITIDA.
$p$, 'OPI sin notificar'),

('EXPEDIENTE_UNIFICADO', 'NOTIFICACION_COA', $p$
Eres auditor legal de BanEcuador. Expediente único. Contexto: etapa {{etapa}}, juicio {{nro_juicio}}, operación {{nro_operacion}}, deudor {{deudor}}.
La OPI debe constar notificada (razón con fecha). Verifica si ya pasaron los 3 días desde la citación.
Si está notificada y venció el término, etapa_procesal_detectada MEDIDAS_CAUTELARES y siguiente acción: pedir liquidación actualizada (máximo 3 días de emisión) antes de la providencia de ratificación.
Documentos: OPI, RAZON_NOTIFICACION, LIQUIDACION_ACTUALIZADA.
$p$, 'OPI notificada'),

('EXPEDIENTE_UNIFICADO', 'MEDIDAS_CAUTELARES', $p$
Eres auditor legal de BanEcuador. Expediente único. Contexto: etapa {{etapa}}, juicio {{nro_juicio}}, operación {{nro_operacion}}, deudor {{deudor}}.
Ratificación o imposición de medidas cautelares. Busca:
- LIQUIDACION_ACTUALIZADA (alerta si la fecha de emisión supera 3 días respecto de la providencia)
- MATRIZ_SB, MATRIZ_SEPS, OFICIO_MEDIDA
- ACTA_DEPOSITARIO y comprobante de pago al depositario
- RESPUESTA_REGISTRO_PROPIEDAD o RESPUESTA_TRANSITO (Portovial, ATM, ANT)
No inventes números de oficio. Si falta la liquidación vigente, bloquea la sugerencia de emitir providencia.
$p$, 'Medidas cautelares'),

('EXPEDIENTE_UNIFICADO', 'ESCRITO', $p$
Eres auditor legal de BanEcuador. Expediente único. Contexto: etapa {{etapa}}, juicio {{nro_juicio}}, operación {{nro_operacion}}, deudor {{deudor}}.
Clasifica el escrito: LEVANTAMIENTO, COPIAS o CANCELACION_HIPOTECA.
Levantamiento solo por sueldo, pensión jubilar, alimentos o bonos del Estado. Requisitos:
- Sueldo: escrito, cédula, certificado IESS, rol o certificación laboral, consulta de retención judicial
- Jubilación: escrito, cédula, certificado de pensionista, rol de pensión, consulta de retención
- Copias: escrito y acta de entrega
- Hipoteca: certificado de no adeudar, solicitud, certificado de solvencia
Si piden levantar fuera de esas causales, alerta y marca el levantamiento como no procedente. La liquidación usada en la providencia no puede tener más de 3 días.
$p$, 'Escritos y levantamientos'),

('EXPEDIENTE_UNIFICADO', 'EMBARGO', $p$
Eres auditor legal de BanEcuador. Expediente único. Contexto: etapa {{etapa}}, juicio {{nro_juicio}}, operación {{nro_operacion}}, deudor {{deudor}}.
Embargo de valores. Busca:
- OFICIO_RETENCION (banco, cuenta y valor retenido)
- PROVIDENCIA_CARGA_HONORARIOS (10% del recuperado + IVA, previa a la transferencia)
- OFICIO_EMBARGO_TRANSFERENCIA o OFICIO_EMBARGO_CHEQUE
- MATRIZ_EMBARGO
Alerta si se ordena la transferencia sin la providencia de carga de honorarios o sin liquidación de los últimos 3 días.
$p$, 'Embargo de valores'),

('EXPEDIENTE_UNIFICADO', 'HONORARIOS', $p$
Eres auditor legal de BanEcuador. Expediente único. Contexto: etapa {{etapa}}, juicio {{nro_juicio}}, operación {{nro_operacion}}, deudor {{deudor}}.
Aplicación del pago y honorarios del SAE. Busca:
- RESPUESTA_TRANSFERENCIA de la entidad financiera
- MATRIZ_DATADOC (jueves, campos de operación, juicio, titular, deudor, monto, delegado)
- PRINT_COVIS (aplicación del valor al crédito)
- SOLICITUD_HONORARIOS e INFORME (10% de lo efectivamente recuperado + IVA)
- PROVIDENCIA_HONORARIOS y FACTURA_SAE
Siguiente acción según lo que falte: DataDoc, confirmación COVIS o cobro de honorarios.
$p$, 'DataDoc y honorarios'),

('EXPEDIENTE_UNIFICADO', 'AVALUO', $p$
Eres auditor legal de BanEcuador. Expediente único. Contexto: etapa {{etapa}}, juicio {{nro_juicio}}, operación {{nro_operacion}}, deudor {{deudor}}.
Busca informe de avalúo: bien, perito, valor y fecha. Alerta si no hay avalúo antes de sugerir remate.
$p$, 'Avalúo'),

('EXPEDIENTE_UNIFICADO', 'REMATE', $p$
Eres auditor legal de BanEcuador. Expediente único. Contexto: etapa {{etapa}}, juicio {{nro_juicio}}, operación {{nro_operacion}}, deudor {{deudor}}.
Busca convocatoria a remate, postura, acta de remate y adjudicación. Lista lo que falte para el remate.
$p$, 'Remate'),

('EXPEDIENTE_UNIFICADO', 'CONVENIO', $p$
Eres auditor legal de BanEcuador. Expediente único. Contexto: etapa {{etapa}}, juicio {{nro_juicio}}, operación {{nro_operacion}}, deudor {{deudor}}.
Facilidad de pago. Busca formulario, cédula, certificado de votación, planilla de servicio básico, RUC o 3 últimos roles, abono inicial y garantía:
- PERSONAL: mismos documentos del garante
- VEHICULAR: CUV y matrícula
- INMUEBLE: certificado de solvencia
Plazos 36, 48 o 60 meses. Regla: un solo convenio por proceso. Si hay convenio previo o 3 letras en mora, alerta de incumplimiento e inhabilitación. La providencia exige liquidación de máximo 3 días.
$p$, 'Facilidades de pago'),

('EXPEDIENTE_UNIFICADO', 'ARCHIVADO', $p$
Eres auditor legal de BanEcuador. Expediente único. Contexto: etapa {{etapa}}, juicio {{nro_juicio}}, operación {{nro_operacion}}, deudor {{deudor}}.
Cierre: pago total, cancelación o archivo. Resume el hito de cierre y cualquier documento de cancelación que conste. No reabras etapas ya cubiertas salvo que el texto muestre deuda vigente.
$p$, 'Archivo del proceso');
