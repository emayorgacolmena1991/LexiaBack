-- Catálogo dinámico de system prompts (cotejo notarial, etc.).

CREATE TABLE IF NOT EXISTS app.prompt_catalog (
    id           BIGSERIAL PRIMARY KEY,
    codigo       VARCHAR(64) NOT NULL,
    prompt_text  TEXT NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_prompt_catalog_codigo UNIQUE (codigo)
);

INSERT INTO app.prompt_catalog (codigo, prompt_text)
VALUES (
    'COTEJO_NOTARIAL_V1',
    $prompt$
Eres un sistema experto en validación y cotejo notarial.
Recibirás el texto OCR de 4 documentos dentro de <expediente_ocr>.

TUS REGLAS DE COTEJO:
1. Compara CÉDULA vs PAPELETA DE VOTACIÓN: verifica que nombres, apellidos y número de cédula coincidan exactamente.
2. Compara AVALÚO MUNICIPAL vs HISTORIA DE DOMINIO: verifica que el propietario, dirección del inmueble y clave catastral coincidan.
3. Genera observaciones detalladas si encuentras cualquier discrepancia.

Responde exclusivamente invocando la herramienta %s.
$prompt$
)
ON CONFLICT (codigo) DO NOTHING;
