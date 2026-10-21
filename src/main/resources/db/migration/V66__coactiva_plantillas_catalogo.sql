-- Catálogo etapa → plantilla .docx → variables que el LLM debe extraer.
-- plantilla_archivo '' conserva la unicidad de los prompts de diagnóstico.

ALTER TABLE app.coactiva_prompt_catalog
    ADD COLUMN plantilla_archivo VARCHAR(255) NOT NULL DEFAULT '',
    ADD COLUMN variables_requeridas JSONB;

ALTER TABLE app.coactiva_prompt_catalog
    DROP CONSTRAINT uq_coactiva_prompt_tipo_etapa;

ALTER TABLE app.coactiva_prompt_catalog
    ADD CONSTRAINT uq_coactiva_prompt_tipo_etapa_plantilla
        UNIQUE (tipo_documento, etapa, plantilla_archivo);

ALTER TABLE app.coactiva_prompt_catalog
    ADD CONSTRAINT coactiva_prompt_plantilla_archivo_check
        CHECK (plantilla_archivo = ''
               OR (plantilla_archivo LIKE 'templates/coactivas/%.docx'
                   AND position('..' IN plantilla_archivo) = 0));

INSERT INTO app.coactiva_plantilla (tenant_id, codigo, nombre, etapa, ruta_docx)
SELECT t.id, v.codigo, v.nombre, v.etapa, v.ruta
FROM control.tenant t
CROSS JOIN (
    VALUES
        ('opi', 'FORMATO ORDEN DE PAGO INMEDIATO.docx', 'OPI_EMITIDA',
         'templates/coactivas/IMPULSO_ORDEN_PAGO_VARIABLES.docx'),
        ('ratificacion-gye', 'FORMATO RATIFICACION DE MEDIDAS GYE.docx', 'MEDIDAS_CAUTELARES',
         'templates/coactivas/RATIFICACION_GYE_VARIABLES.docx'),
        ('ratificacion-portoviejo', 'FORMATO RATIFICACION PORTOVIEJO.docx', 'MEDIDAS_CAUTELARES',
         'templates/coactivas/RATIFICACION_PORTOVIEJO_VARIABLES.docx'),
        ('ratificacion-portoviejo-2', 'FORMATO RATIFICACION PORTOVIEJO 2.docx', 'MEDIDAS_CAUTELARES',
         'templates/coactivas/RATIFICACION_PORTOVIEJO_VARIABLES_2.docx'),
        ('ratificacion-chone', 'FORMATO RATIFICACION CHONE - EL CARMEN.docx', 'MEDIDAS_CAUTELARES',
         'templates/coactivas/RATIFICACION_CHONE_VARIABLES.docx'),
        ('escrito-providencia', 'PROVIDENCIA ATENCION ESCRITO.docx', 'ESCRITO',
         'templates/coactivas/FORMATO_PROV_CONTESTACION_ESCRITO_TEMPLATE.docx'),
        ('acta-entrega-copias', 'ACTA DE ENTREGA DE COPIAS CERTIFICADAS.docx', 'ESCRITO',
         'templates/coactivas/ACTA_ENTREGA_COPIAS_CERTIFICADAS.docx'),
        ('convenio-traslado', 'PROVIDENCIA CORRER TRASLADO.docx', 'CONVENIO',
         'templates/coactivas/CORRER_TRASLADO_FACILIDAD_PAGO.docx'),
        ('embargo-transferencia', 'FORMATO EMBARGO DE VALORES TRANSFERENCIA.docx', 'EMBARGO',
         'templates/coactivas/EMBARGO_VALORES_TRANSFERENCIA.docx'),
        ('embargo-oficio', 'OFICIO DE EMBARGO TRANSFERENCIA.docx', 'EMBARGO',
         'templates/coactivas/EMBARGO_TRANSFERENCIA.docx'),
        ('oficio-embargo', 'OFICIO DE EMBARGO.docx', 'EMBARGO',
         'templates/coactivas/OFICIO_EMBARGO_VARIABLES.docx'),
        ('embargo-cheque', 'ACTA POSESION DEPOSITARIO JUDICIAL.docx', 'EMBARGO',
         'templates/coactivas/ACTA_POSESION_DEPOSITARIO_JUDICIAL_EMBARGO_CHEQUE.docx'),
        ('honorarios-solicitud', 'SOLICITUD CARGA HONORARIOS EMBARGO.docx', 'HONORARIOS',
         'templates/coactivas/SOLICITUD_CARGA_HONORARIOS_EMBARGO_TRANSFERENCIA.docx'),
        ('honorarios-providencia', 'PROV. COBRO DE HONORARIOS POR EMBARGO.docx', 'HONORARIOS',
         'templates/coactivas/FORMATO_PROV_COBRO_HONORARIOS_EMBARGO_TEMPLATE.docx'),
        ('honorarios-informe', 'INFORME COBRO HONORARIOS POR EMBARGO.docx', 'HONORARIOS',
         'templates/coactivas/INFORME_HONORARIOS_EMBARGO_VARIABLES_ANEXOS.docx'),
        ('honorarios-abono-providencia', 'PROV. COBRO DE HONORARIOS POR ABONO.docx', 'HONORARIOS',
         'templates/coactivas/FORMATO_PROV_COBRO_HONORARIOS_POR_ABONO_TEMPLATE.docx'),
        ('honorarios-abono-informe', 'INFORME COBRO HONORARIOS POR ABONO.docx', 'HONORARIOS',
         'templates/coactivas/INFORME_HONORARIOS_ABONO_VARIABLES.docx')
) AS v(codigo, nombre, etapa, ruta)
WHERE t.deleted_at IS NULL
ON CONFLICT (tenant_id, codigo) DO UPDATE
    SET nombre = EXCLUDED.nombre,
        etapa = EXCLUDED.etapa,
        ruta_docx = EXCLUDED.ruta_docx,
        activo = TRUE;

INSERT INTO app.coactiva_prompt_catalog
    (tipo_documento, etapa, system_prompt, descripcion, plantilla_archivo, variables_requeridas)
VALUES
('PLANTILLA', 'OPI_EMITIDA',
 $p$Analiza el OCR y extrae en JSON solo las variables de esta plantilla. Si un dato no consta literalmente, usa null. No inventes.$p$,
 'Impulso orden de pago',
 'templates/coactivas/IMPULSO_ORDEN_PAGO_VARIABLES.docx',
 '["abogado_secretario","agencia","cedula_deudor_principal_1","cedula_deudor_principal_2","cedula_garante_solidario_1","fecha_actuacion","fecha_orden_pago_inmediato","fecha_resolucion_delegacion","funcionario_coactiva","hora_actuacion","monto_credito_original","monto_credito_original_letras","monto_deuda_total","monto_deuda_total_letras","nombre_deudor_principal_1","nombre_deudor_principal_2","nombre_garante_solidario_1","nombre_gerente_general","numero_juicio_coactivo","numero_operacion","numero_resolucion_delegacion"]'::jsonb),

('PLANTILLA', 'MEDIDAS_CAUTELARES',
 $p$Analiza el OCR y extrae en JSON solo las variables de esta plantilla. Si un dato no consta literalmente, usa null. No inventes.$p$,
 'Ratificación Guayaquil',
 'templates/coactivas/RATIFICACION_GYE_VARIABLES.docx',
 '["abogado_secretario","cedula_deudor_principal_1","ciudad_actuacion","fecha_actuacion","fecha_liquidacion","fecha_resolucion_delegacion","funcionario_coactiva","hora_actuacion","monto_deuda_total","monto_deuda_total_letras","nombre_deudor_principal_1","nombre_gerente_general","numero_juicio_coactivo","numero_operacion","numero_resolucion_delegacion","unidad_ejecucion_coactiva"]'::jsonb),

('PLANTILLA', 'MEDIDAS_CAUTELARES',
 $p$Analiza el OCR y extrae en JSON solo las variables de esta plantilla. Si un dato no consta literalmente, usa null. No inventes.$p$,
 'Ratificación Portoviejo',
 'templates/coactivas/RATIFICACION_PORTOVIEJO_VARIABLES.docx',
 '["abogado_secretario","cedula_deudor_principal_1","cedula_garante_solidario_1","ciudad_actuacion","correo_notificacion_deudor","fecha_actuacion","fecha_liquidacion","fecha_resolucion_delegacion","funcionario_coactiva","hora_actuacion","monto_deuda_total","monto_deuda_total_letras","monto_retencion","monto_retencion_letras","nombre_deudor_principal_1","nombre_garante_solidario_1","nombre_gerente_general","numero_juicio_coactivo","numero_operacion","numero_resolucion_delegacion"]'::jsonb),

('PLANTILLA', 'MEDIDAS_CAUTELARES',
 $p$Analiza el OCR y extrae en JSON solo las variables de esta plantilla. Si un dato no consta literalmente, usa null. No inventes.$p$,
 'Ratificación Portoviejo 2',
 'templates/coactivas/RATIFICACION_PORTOVIEJO_VARIABLES_2.docx',
 '["abogado_secretario","cedula_deudor_principal_1","cedula_garante_solidario_1","ciudad_actuacion","correo_notificacion_deudor","fecha_actuacion","fecha_liquidacion","fecha_resolucion_delegacion","funcionario_coactiva","hora_actuacion","monto_deuda_total","monto_deuda_total_letras","monto_retencion","monto_retencion_letras","nombre_deudor_principal_1","nombre_garante_solidario_1","nombre_gerente_general","numero_juicio_coactivo","numero_operacion","numero_resolucion_delegacion"]'::jsonb),

('PLANTILLA', 'MEDIDAS_CAUTELARES',
 $p$Analiza el OCR y extrae en JSON solo las variables de esta plantilla. Si un dato no consta literalmente, usa null. No inventes.$p$,
 'Ratificación Chone',
 'templates/coactivas/RATIFICACION_CHONE_VARIABLES.docx',
 '["abogado_secretario","cedula_deudor_principal_1","cedula_garante_solidario_1","ciudad_actuacion","correo_notificacion_deudor","fecha_actuacion","fecha_liquidacion","fecha_resolucion_delegacion","funcionario_coactiva","hora_actuacion","monto_deuda_total","monto_deuda_total_letras","monto_retencion","monto_retencion_letras","nombre_deudor_principal_1","nombre_garante_solidario_1","nombre_gerente_general","numero_juicio_coactivo","numero_operacion","numero_resolucion_delegacion"]'::jsonb),

('PLANTILLA', 'ESCRITO',
 $p$Analiza el OCR y extrae en JSON solo las variables de esta plantilla. Si un dato no consta literalmente, usa null. No inventes.$p$,
 'Contestación de escrito',
 'templates/coactivas/FORMATO_PROV_CONTESTACION_ESCRITO_TEMPLATE.docx',
 '["banco_embargado","cedula_deudor_principal_1","cedula_deudor_principal_2","cedula_garante_solidario_1","cedula_garante_solidario_2","ciudad_actuacion","correo_notificacion_1","correo_notificacion_2","correo_notificacion_3","fecha_actuacion","fecha_escrito_presentado","fecha_liquidacion_actualizada","fecha_resolucion_delegacion","hora_actuacion","monto_deuda_total","monto_deuda_total_letras","nombre_abogado_secretario","nombre_deudor_principal_1","nombre_deudor_principal_2","nombre_funcionario_coactiva","nombre_garante_solidario_1","nombre_garante_solidario_2","nombre_gerente_general","numero_cuenta_1","numero_juicio_coactivo","numero_operacion","numero_resolucion_delegacion","numero_zonal"]'::jsonb),

('PLANTILLA', 'ESCRITO',
 $p$Analiza el OCR y extrae en JSON solo las variables de esta plantilla. Si un dato no consta literalmente, usa null. No inventes.$p$,
 'Acta de entrega de copias',
 'templates/coactivas/ACTA_ENTREGA_COPIAS_CERTIFICADAS.docx',
 '["anio_entrega","cedula_receptor","dia_entrega","foja_fin_numero","foja_fin_texto","foja_inicio_numero","foja_inicio_texto","mes_entrega","nombre_receptor","nombre_secretario","numero_proceso_coactivo","titulo_secretario"]'::jsonb),

('PLANTILLA', 'CONVENIO',
 $p$Analiza el OCR y extrae en JSON solo las variables de esta plantilla. Si un dato no consta literalmente, usa null. No inventes.$p$,
 'Correr traslado facilidad de pago',
 'templates/coactivas/CORRER_TRASLADO_FACILIDAD_PAGO.docx',
 '["cedula_deudor_1","cedula_deudor_2","cedula_garante_1","ciudad","ciudad_sucursal","correo_notificacion_1","correo_notificacion_2","fecha_convenio","fecha_liquidacion","fecha_memorandum_convenio","fecha_memorandum_traslado","fecha_providencia","fecha_resolucion_facilidad","hora","monto_liquidacion","monto_liquidacion_letras","nombre_delegado","nombre_deudor_1","nombre_deudor_2","nombre_garante_1","nombre_gerente_sucursal","nombre_secretario","nombre_secretario_zonal","numero_cuotas","numero_juicio","numero_memorandum_convenio","numero_memorandum_traslado","numero_operacion","numero_resolucion_facilidad","numero_sucursal_provincial","numero_zona","titulo_delegado","titulo_gerente_sucursal","titulo_secretario","titulo_secretario_zonal","zona"]'::jsonb),

('PLANTILLA', 'EMBARGO',
 $p$Analiza el OCR y extrae en JSON solo las variables de esta plantilla. Si un dato no consta literalmente, usa null. No inventes.$p$,
 'Providencia embargo de valores',
 'templates/coactivas/EMBARGO_VALORES_TRANSFERENCIA.docx',
 '["banco_retencion","calidad_embargado","cedula_deudor_1","cedula_deudor_2","cedula_embargado","cedula_garante_1","cedula_garante_2","ciudad","correo_abogado_externo","correo_coordinacion","correo_delegado","correo_notificacion_coactiva","fecha_liquidacion","fecha_providencia","fecha_resolucion_delegacion","hora","monto_embargo_total","monto_embargo_total_letras","monto_honorarios","monto_honorarios_letras","monto_liquidacion","monto_liquidacion_letras","monto_retencion_1","monto_retencion_1_letras","monto_retencion_2","monto_retencion_2_letras","nombre_delegado","nombre_deudor_1","nombre_deudor_2","nombre_embargado","nombre_garante_1","nombre_garante_2","nombre_gerente_general","nombre_secretario","numero_cuenta_destino","numero_cuenta_embargada","numero_cuenta_honorarios","numero_juicio","numero_oficio_retencion_1","numero_oficio_retencion_2","numero_operacion","numero_resolucion_delegacion","numero_zona","tipo_cuenta_destino","titulo_delegado","titulo_gerente_general","titulo_secretario","zona"]'::jsonb),

('PLANTILLA', 'EMBARGO',
 $p$Analiza el OCR y extrae en JSON solo las variables de esta plantilla. Si un dato no consta literalmente, usa null. No inventes.$p$,
 'Oficio embargo transferencia',
 'templates/coactivas/EMBARGO_TRANSFERENCIA.docx',
 '["cedula_deudor_principal_1","ciudad_actuacion","ciudad_zonal","cliente_beneficiario","correo_abogado_secretario","correo_adicional_1","correo_funcionario_coactiva","correo_notificacion_coactiva","entidad_financiera_destino","fecha_actuacion","institucion_financiera","monto_embargo_1","nombre_abogado_secretario","nombre_deudor_principal_1","nombre_funcionario_coactiva","numero_cuenta_destino","numero_cuenta_retencion_1","numero_juicio_coactivo","numero_oficio","numero_zonal","objetivo_medida","ruc_entidad_financiera","tipo_cuenta_destino"]'::jsonb),

('PLANTILLA', 'EMBARGO',
 $p$Analiza el OCR y extrae en JSON solo las variables de esta plantilla. Si un dato no consta literalmente, usa null. No inventes.$p$,
 'Oficio de embargo',
 'templates/coactivas/OFICIO_EMBARGO_VARIABLES.docx',
 '["cedula_depositario_judicial","cedula_deudor_principal_1","cedula_garante_solidario_1","ciudad_actuacion","fecha_actuacion","institucion_financiera","monto_embargo_1","monto_embargo_1_letras","monto_embargo_2","monto_embargo_2_letras","monto_embargo_3","monto_embargo_3_letras","nombre_abogado_secretario","nombre_depositario_judicial","nombre_deudor_principal_1","nombre_funcionario_coactiva","nombre_garante_solidario_1","numero_cuenta_1","numero_cuenta_2","numero_cuenta_3","numero_juicio_coactivo","numero_oficio"]'::jsonb),

('PLANTILLA', 'EMBARGO',
 $p$Analiza el OCR y extrae en JSON solo las variables de esta plantilla. Si un dato no consta literalmente, usa null. No inventes.$p$,
 'Acta depositario judicial',
 'templates/coactivas/ACTA_POSESION_DEPOSITARIO_JUDICIAL_EMBARGO_CHEQUE.docx',
 '["cedula_depositario","ciudad","fecha_acta","fecha_auto_designacion","nombre_delegado","nombre_depositario","nombre_secretario","numero_proceso","numero_zona","titulo_delegado","titulo_depositario","titulo_secretario","zona"]'::jsonb),

('PLANTILLA', 'EMBARGO',
 $p$Analiza el OCR y extrae en JSON solo las variables de esta plantilla. Si un dato no consta literalmente, usa null. No inventes.$p$,
 'Providencia de embargo',
 'templates/coactivas/FORMATO_PROV_EMBARGO_TEMPLATE.docx',
 '["banco_embargado","cedula_depositario_judicial","cedula_deudor_principal","cedula_garante_solidario","ciudad_actuacion","correo_coactiva_institucional","correo_estudio_juridico_externo","correo_funcionario_coactiva","correo_notificacion_deudor","cuenta_honorarios_abogado","fecha_actuacion","fecha_resolucion_delegacion","hora_actuacion","monto_deuda_total","monto_deuda_total_letras","monto_embargo_1","monto_embargo_1_letras","monto_embargo_2","monto_embargo_2_letras","monto_embargo_3","monto_embargo_3_letras","monto_honorarios","monto_honorarios_letras","nombre_abogado_secretario","nombre_depositario_judicial","nombre_deudor_principal","nombre_funcionario_coactiva","nombre_garante_solidario","nombre_gerente_general","numero_cuenta_1","numero_cuenta_2","numero_cuenta_3","numero_juicio_coactivo","numero_operacion","numero_resolucion_delegacion","numero_zonal"]'::jsonb),

('PLANTILLA', 'HONORARIOS',
 $p$Analiza el OCR y extrae en JSON solo las variables de esta plantilla. Si un dato no consta literalmente, usa null. No inventes.$p$,
 'Solicitud carga honorarios',
 'templates/coactivas/SOLICITUD_CARGA_HONORARIOS_EMBARGO_TRANSFERENCIA.docx',
 '["anio_embargo","anio_factura","calidad_coactivado","cedula_coactivado","dia_embargo","institucion_financiera","mes_embargo","mes_factura","nombre_cliente","nombre_coactivado","nombre_institucion_bancaria","numero_cuenta_retencion","numero_operacion","numero_proceso_coactivo","valor_embargo_num","valor_embargo_texto","valor_honorarios_subtotal","valor_honorarios_total_num","valor_honorarios_total_texto","valor_iva_honorarios"]'::jsonb),

('PLANTILLA', 'HONORARIOS',
 $p$Analiza el OCR y extrae en JSON solo las variables de esta plantilla. Si un dato no consta literalmente, usa null. No inventes.$p$,
 'Providencia cobro honorarios por embargo',
 'templates/coactivas/FORMATO_PROV_COBRO_HONORARIOS_EMBARGO_TEMPLATE.docx',
 '["cedula_deudor_principal_1","cedula_garante_solidario_1","cedula_garante_solidario_2","ciudad_actuacion","ciudad_zonal","cuenta_honorarios_abogado","fecha_actuacion","fecha_aplicacion_cobis","fecha_embargo","fecha_liquidacion_actualizada","fecha_resolucion_delegacion","hora_actuacion","monto_deuda_total","monto_deuda_total_letras","monto_embargo_1","monto_embargo_1_letras","monto_embargo_2","monto_embargo_2_letras","monto_honorarios","monto_honorarios_letras","nombre_abogado_secretario","nombre_deudor_principal_1","nombre_funcionario_coactiva","nombre_garante_solidario_1","nombre_garante_solidario_2","nombre_gerente_general","numero_juicio_coactivo","numero_operacion","numero_resolucion_delegacion","numero_zonal"]'::jsonb),

('PLANTILLA', 'HONORARIOS',
 $p$Analiza el OCR y extrae en JSON solo las variables de esta plantilla. Si un dato no consta literalmente, usa null. No inventes.$p$,
 'Informe honorarios por embargo',
 'templates/coactivas/INFORME_HONORARIOS_EMBARGO_VARIABLES_ANEXOS.docx',
 '["abogado_secretario","anexo_1","anexo_2","cedula_deudor_principal_1","cedula_garante_solidario_1","cedula_garante_solidario_2","cuenta_honorarios_abogado","embargo","fecha_actuacion","fecha_embargo","funcionario_coactiva","hora_actuacion","iva","monto_embargo_1","monto_embargo_1_letras","monto_embargo_2","monto_embargo_2_letras","monto_honorarios","monto_honorarios_letras","nombre_deudor_principal_1","nombre_garante_solidario_1","nombre_garante_solidario_2","numero_juicio_coactivo","numero_operacion","total","valor"]'::jsonb),

('PLANTILLA', 'HONORARIOS',
 $p$Analiza el OCR y extrae en JSON solo las variables de esta plantilla. Si un dato no consta literalmente, usa null. No inventes.$p$,
 'Providencia cobro honorarios por abono',
 'templates/coactivas/FORMATO_PROV_COBRO_HONORARIOS_POR_ABONO_TEMPLATE.docx',
 '["cedula_deudor_principal_1","cedula_deudor_principal_2","cedula_garante_solidario_1","ciudad_actuacion","ciudad_zonal","correo_notificacion_deudor","cuenta_honorarios_abogado","fecha_actuacion","fecha_comprobante_pago","fecha_liquidacion","hora_actuacion","monto_abono","monto_abono_letras","monto_deuda_total","monto_deuda_total_letras","monto_honorarios","monto_honorarios_letras","nombre_abogado_secretario","nombre_deudor_principal_1","nombre_deudor_principal_2","nombre_funcionario_coactiva","nombre_garante_solidario_1","numero_comprobante_pago","numero_juicio_coactivo","numero_operacion","numero_zonal"]'::jsonb),

('PLANTILLA', 'HONORARIOS',
 $p$Analiza el OCR y extrae en JSON solo las variables de esta plantilla. Si un dato no consta literalmente, usa null. No inventes.$p$,
 'Informe honorarios por abono',
 'templates/coactivas/INFORME_HONORARIOS_ABONO_VARIABLES.docx',
 '["abogado_secretario","abono","anexo_1","cedula_deudor_principal_1","cedula_deudor_principal_2","cedula_garante_solidario_1","cedula_garante_solidario_2","cuenta_honorarios_abogado","detalle_abono","detalle_abono_texto","fecha_actuacion","fecha_comprobante_pago","hora_actuacion","iva","monto_abono","monto_abono_letras","monto_honorarios","monto_honorarios_letras","nombre_deudor_principal_1","nombre_deudor_principal_2","nombre_garante_solidario_1","nombre_garante_solidario_2","numero_juicio_coactivo","numero_operacion","total","valor"]'::jsonb)
ON CONFLICT (tipo_documento, etapa, plantilla_archivo) DO UPDATE
    SET system_prompt = EXCLUDED.system_prompt,
        descripcion = EXCLUDED.descripcion,
        variables_requeridas = EXCLUDED.variables_requeridas,
        activo = TRUE,
        updated_at = now();
