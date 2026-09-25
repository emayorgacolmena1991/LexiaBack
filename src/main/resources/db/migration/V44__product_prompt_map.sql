-- Mapeo producto BIESS → prompt_key (texto del prompt vive en YML / PromptRegistryService).
-- V43 ya usado por escrituración abogado; este es el mapa del TICKET-DEV-703.

CREATE TABLE IF NOT EXISTS app.product_prompt_map (
    id           BIGSERIAL PRIMARY KEY,
    product_code VARCHAR(60) NOT NULL,
    prompt_key   VARCHAR(100) NOT NULL,
    description  VARCHAR(255),
    active       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_product_prompt_map_product UNIQUE (product_code)
);

CREATE INDEX IF NOT EXISTS ix_product_prompt_map_active
    ON app.product_prompt_map (active)
    WHERE active = TRUE;

INSERT INTO app.product_prompt_map (product_code, prompt_key, description) VALUES
    ('VIV_HIPOTECADA_BIESS', 'PROMPT_COMPRAVENTA_BIESS',
     'Validación Cédula vs Papeleta y Avalúo vs CHD con gravamen'),
    ('VIV_TERMINADA_IND', 'PROMPT_VIVIENDA_TERMINADA',
     'Validación estándar vivienda individual'),
    ('VIV_TERMINADA_PREF', 'PROMPT_VIVIENDA_TERMINADA',
     'Validación vivienda terminada preferencial'),
    ('VIV_TERMINADA_SOLID', 'PROMPT_VIVIENDA_TERMINADA',
     'Validación vivienda terminada solidaria (multi-parte)'),
    ('VIV_TERMINADA_COMPANIA', 'PROMPT_VIVIENDA_COMPANIA',
     'Validación RUC, Representante Legal, Junta y Nombramiento'),
    ('SUSTITUCION_HIPOTECA', 'PROMPT_SUSTITUCION_HIPOTECA',
     'Validación de liquidación de hipoteca anterior y CHD'),
    ('TERRENO_Y_VIVIENDA', 'PROMPT_VIVIENDA_TERMINADA',
     'Validación terreno / vivienda+terreno')
ON CONFLICT (product_code) DO NOTHING;
