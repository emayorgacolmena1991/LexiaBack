-- Texto OCR (Azure) + análisis Gemini por documento de borrador/expediente.

CREATE TABLE IF NOT EXISTS app.documento_texto_ocr (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID,
    id_expediente       VARCHAR(64) NOT NULL,
    id_documento        VARCHAR(64) NOT NULL,
    tipo_documento      VARCHAR(64),
    texto_ocr           TEXT,
    analisis_json       TEXT,
    estado_doc          VARCHAR(32) NOT NULL DEFAULT 'EN_PROCESO',
    motivo              TEXT,
    confianza           INTEGER,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id_expediente, id_documento)
);

CREATE INDEX IF NOT EXISTS ix_documento_texto_ocr_exp
    ON app.documento_texto_ocr (id_expediente);
