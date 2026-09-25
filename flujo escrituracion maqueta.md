Maqueta flujo escrituración BIESS ↔ BD Lexia (EJD)
Fuentes: docx levantamiento + process_definition EJD e1–e8. Sin implementación — solo mapeo.

6
Productos Drive
8
Etapas BD EJD
10+
Etapas operativas docx
5
Actos catalogados
Desalineación principal
Docx: hoja de ruta BIESS con ~10 etapas + productos hipotecarios. BD hoy: proceso genérico EJD e1→e8 + actos COMPRAVENTA/HIPOTECA/etc. Falta capa producto/subproducto y peritaje BIESS como etapa formal.
1. Flujo operativo → etapa BD
Caja = etapa docx. Etiqueta inferior = código process_stage_def + tablas.

Recepción expediente BIESS
e1 Recepción
legal_case + writing_file
Selección producto / subproducto
e1 (falta catálogo producto)
catalog ESC_OPERATION_TYPE (parcial)
Estudio de título + OCR/IA
e2 Estudio de título
title_study, document, extracted_data, case_validation V1–V6
¿Título OK?
gate_def G-002
case_gate
Peritaje técnico BIESS
sin etapa dedicada
case_task / case_action (workaround)
Retiro físico + validación
e2→e3 o e4
document_version (UPLOAD)
Regularización docs (3.1)
e3 Observaciones
title_observation, case_exception, gate G-003
Minutas compraventa + hipoteca
e4 Preparación jurídica
minuta_draft
Contrato de mutuo BIESS
e4 (mismo bucket)
minuta_draft / document
Entrega a notaría
e5 Notaría / matrización
notary_act + ejd_stage_integration NOTARIA
Liquidación municipal
e6 Impuestos municipales
municipal_tax + MUNICIPIO/PAGOS
Firma afiliado/vendedor
e7 Cierre (parcial)
notary_act.status
Firma apoderado BIESS
e7 (parcial)
case_action / human_decision
Cierre protocolo + testimonios
e7 Cierre de escritura
notary_act
Registro de la Propiedad
e8 Registro / inscripción
registry_inscription + REGISTRO
Inscrito → desembolso BIESS
e8 done
legal_case.status
2. Cadena BD actual (lineal)
Transiciones: process_stage_transition e1→…→e8. Conectores: e5=NOTARIA, e6=MUNICIPIO/PAGOS, e8=REGISTRO/QUIPUX.

3. Productos Drive → cobertura BD
Producto	Entradas	Formatos	BD hoy	Gap
Vivienda hipotecada BIESS	Avalúo, CHD, escritura adquisición, cédula solicitante, cédula vendedor	Contrato mutuo vivienda hipotecada + Minuta compraventa & hipoteca	HIPOTECA / COMPRAVENTA genéricos	Falta producto BIESS + matriz docs específica
Vivienda terminada individual / preferencial	Cédulas, avalúo, CHD, predial/bomberos según cantón	Minuta preferencial/individual + mutuo regular	COMPRAVENTA + HIPOTECA	Sin subproducto Individual/Preferencial/Solidaria
Vivienda terminada solidaria	Cédulas compradores/vendedores, avalúo, CHD	Minuta solidaria + mutuo regular/preferencial	COMPRAVENTA	Multi-parte (case_party) sí; plantilla no
Vivienda terminada con compañía	Avalúo, no poseer bienes, CHD, comprador, RUC/habilitantes vendedor	Minuta CV (compañía) + hipoteca/mutuo (abogada)	PODER, ESCRITURA_ANT en DOC_TYPE	Flag compañía + acta junta / nombramiento RL
Sustitución de hipoteca	Cédula, CHD, liquidación hipoteca anterior	Mutuo sustitución + minuta hipoteca	HIPOTECA	Código operación SUSTITUCION ausente
Terreno / vivienda+terreno individual	Cédula, avalúo, CHD, predial	Contrato vivienda terminada y terreno + minuta terreno	COMPRAVENTA	Código TERRENO ausente
4. Matrices documentales existentes (conflicto)
Acto BD	act_requirement (V40)	ejd_operation_document_req (V19)	Nota
COMPRAVENTA	CEDULA, PAPELETA, HISTORIA_DOMINIO, AVALUO	CERT_TRADICION, LIBERTAD_GRAV, AVALUO	Dos matrices coexisten — unificar o mapear sinónimos
HIPOTECA	CEDULA, PAPELETA, HISTORIA_DOMINIO, AVALUO, MINUTA, CARTA_APROBACION*	CERT_TRADICION, LIBERTAD_GRAV, MINUTA	*opcional en act_requirement
DONACION	CEDULA, PAPELETA, HISTORIA_DOMINIO, ESCRITURA_ANTECEDENTE, PAGO_IMPUESTO	CERT_TRADICION, LIBERTAD_GRAV	Fuera del flujo BIESS principal del docx
5. Modelo de datos EJD ya disponible
Núcleo expediente
legal_case (case_type=EJD)

case_party, case_stage, case_gate

document → document_version

extracted_data + data_provenance

case_validation (V1–V6 / EJD.V1–V6)

Extensión writing (V4)
writing_file (act_type, municipality)

title_study + title_observation

minuta_draft

notary_act

municipal_tax

registry_inscription

6. Gaps a cerrar (cuando implementes)
Capacidad	Estado	Propuesta maqueta
Productos BIESS (6 subproductos)	Ausente	Nuevo catalog ESC_PRODUCTO o ampliar ESC_OPERATION_TYPE
Peritaje técnico BIESS	Ausente como etapa	Nueva stage e2b o case_task tipada + gate
Firma particular vs apoderado BIESS	Colapsado en e7	Subestados en notary_act o 2 etapas
Cantón → requisitos extra	writing_file.municipality existe	Reglas por cantón (Bomberos, no adeudar) vía rule_def
Vigencia 60 días docs	V3 Vigencia (manual)	Automatizar con extracted_data + dias_max
Plantillas .docx por producto	Sin tabla plantilla	Tabla template_def ligada a producto
Clasificación carpetas Drive → tablas
Carpeta Drive	Uso en Lexia
01_ENTRADAS	document + document_version (UPLOAD) + act_requirement
02_FORMATOS_Y_PLANTILLAS	Futuro template_def → minuta_draft
03_VALIDACIONES_Y_CHECKLISTS	validation_def, gate_def, rule_def
04_DOCUMENTOS_GENERADOS	minuta_draft, document origin generado
05_COMUNICACIONES	user_notifications / case_note
06_REPORTES_Y_CONTROLES	sla_clock, case_task, audit_event
07_DOCUMENTOS_TERCEROS	document + integrations NOTARIA/MUNICIPIO/REGISTRO
08_NORMATIVA	Fuera de runtime (referencia)
Próximo paso (cuando digas implementar)
1) Catálogo productos BIESS. 2) Unificar matriz docs. 3) Alinear etapas docx con e1–e8 (o extender stages). 4) Ligar plantillas .docx a producto. OCR/IA ya encaja en e2 vía extracted_data + case_validation.