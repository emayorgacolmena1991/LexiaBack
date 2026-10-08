-- Validación documental asíncrona con IA para coactivas:
--   * catálogo de prompts por tipo de documento y etapa (configurable en BD),
--   * estado/resultado de la IA por archivo + auditoría del override humano.

-- ---------------------------------------------------------------------------
-- 1. Catálogo de prompts (global, sin tenant: reglas BanEcuador)
-- ---------------------------------------------------------------------------
CREATE TABLE app.coactiva_prompt_catalog (
    id              BIGSERIAL PRIMARY KEY,
    tipo_documento  VARCHAR(32) NOT NULL,          -- tipo de app.coactiva_archivo o '*'
    etapa           VARCHAR(32) NOT NULL DEFAULT '*', -- CoactivaEtapa.name() o '*'
    system_prompt   TEXT NOT NULL,
    descripcion     VARCHAR(255),
    activo          BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_coactiva_prompt_tipo_etapa UNIQUE (tipo_documento, etapa)
);

GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE app.coactiva_prompt_catalog TO lexia_app;
GRANT SELECT ON TABLE app.coactiva_prompt_catalog TO lexia_readonly;
GRANT USAGE, SELECT ON SEQUENCE app.coactiva_prompt_catalog_id_seq TO lexia_app;

INSERT INTO app.coactiva_prompt_catalog (tipo_documento, etapa, system_prompt, descripcion) VALUES
('*', '*', $prompt$
Eres un auditor legal de BanEcuador especializado en procesos coactivos.
Recibirás el texto (OCR) de un documento cargado a un expediente coactivo.
Verifica que el documento sea legible, pertinente al proceso coactivo y que
mencione a BanEcuador o al deudor. Si el texto está vacío, ilegible o no guarda
relación con un proceso coactivo, RECHAZA e indica el motivo concreto.
$prompt$, 'Fallback genérico para cualquier documento/etapa'),

('EXPEDIENTE_ESCANEADO', '*', $prompt$
Eres un auditor legal de BanEcuador especializado en procesos coactivos.
Recibirás el texto (OCR) de un expediente escaneado. Debe contener el PAGARÉ o
título de crédito y la LIQUIDACIÓN previa. Verifica:
1. Que el texto corresponda a un pagaré / título ejecutivo (palabras como "pagaré",
   "título de crédito", "orden de pago").
2. Que conste la palabra "BanEcuador" (o "Banco Nacional de Fomento").
3. Que existan indicios de firma del deudor y, si aplica, del deudor solidario/garante.
4. Que consten monto y fecha de vencimiento.
Si falta un elemento obligatorio, RECHAZA y explica cuál falta.
$prompt$, 'Pagaré y liquidación previa'),

('LIQUIDACION', '*', $prompt$
Eres un auditor legal de BanEcuador especializado en procesos coactivos.
Recibirás el texto (OCR) de una liquidación de crédito. Verifica:
1. Que sea una liquidación (capital, intereses, mora, gastos, total).
2. Que indique número de operación y/o juicio coactivo.
3. Que tenga fecha de corte reciente y totales numéricos coherentes.
Si no es una liquidación o faltan totales, RECHAZA e indica el motivo.
$prompt$, 'Liquidación actualizada'),

('RESPUESTA_ENTIDAD', '*', $prompt$
Eres un auditor legal de BanEcuador especializado en procesos coactivos.
Recibirás el texto (OCR) de una respuesta de entidad de control (Superintendencia
de Bancos, SEPS, Registro de la Propiedad, ANT, etc.). Verifica:
1. Que identifique a la entidad emisora y al deudor consultado.
2. Que responda a una medida cautelar (retención, prohibición de enajenar, etc.).
3. Que tenga fecha y número de oficio.
Si no es una respuesta de entidad o no identifica al deudor, RECHAZA.
$prompt$, 'Respuesta de entidades (SuperBancos / SEPS)'),

('PROVIDENCIA', '*', $prompt$
Eres un auditor legal de BanEcuador especializado en procesos coactivos.
Recibirás el texto (OCR) de una providencia coactiva. Verifica que identifique el
juicio coactivo, al juez/delegado de coactivas, que tenga fecha y una parte
resolutiva clara. RECHAZA si falta la firma del delegado o el número de juicio.
$prompt$, 'Providencias'),

('ESCRITO', '*', $prompt$
Eres un auditor legal de BanEcuador especializado en procesos coactivos.
Recibirás el texto (OCR) de un escrito presentado por el coactivado o su defensa.
Verifica que identifique el juicio coactivo, al compareciente, su petición concreta
y que tenga firma o firma de abogado patrocinador. RECHAZA si no se identifica el
juicio o la petición.
$prompt$, 'Escritos del coactivado');

-- ---------------------------------------------------------------------------
-- 2. Resultado de IA en el archivo + override humano
-- ---------------------------------------------------------------------------
ALTER TABLE app.coactiva_archivo
    ADD COLUMN estado_ia          VARCHAR(16) NOT NULL DEFAULT 'NO_APLICA'
        CHECK (estado_ia IN ('NO_APLICA', 'ANALIZANDO', 'APROBADO', 'RECHAZADO', 'ERROR', 'APROBADO_MANUAL')),
    ADD COLUMN motivo_rechazo_ia  TEXT,
    ADD COLUMN confianza_ia       SMALLINT,
    ADD COLUMN checklist_ia       TEXT,          -- JSON array serializado
    ADD COLUMN etapa_ia           VARCHAR(32),   -- etapa usada para resolver el prompt
    ADD COLUMN ia_analizado_at    TIMESTAMPTZ,
    ADD COLUMN override_por       UUID,
    ADD COLUMN override_at        TIMESTAMPTZ,
    ADD COLUMN override_motivo    VARCHAR(600);

CREATE INDEX ix_coactiva_archivo_estado_ia ON app.coactiva_archivo (tenant_id, expediente_id, estado_ia)
    WHERE deleted_at IS NULL;
