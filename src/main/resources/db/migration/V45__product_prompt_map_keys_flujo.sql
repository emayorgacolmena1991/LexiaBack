-- Alinea product_prompt_map con matriz flujo.md (TICKET-DEV-704).
UPDATE app.product_prompt_map SET prompt_key = 'PROMPT_VIV_INDIVIDUAL',
    description = 'Cédula vs Papeleta, estado civil, vigencia < 60 días'
WHERE product_code = 'VIV_TERMINADA_IND';

UPDATE app.product_prompt_map SET prompt_key = 'PROMPT_VIV_PREFERENCIAL',
    description = 'Requisitos vivienda preferencial (no poseer bienes + límites avalúo)'
WHERE product_code = 'VIV_TERMINADA_PREF';

UPDATE app.product_prompt_map SET prompt_key = 'PROMPT_VIV_SOLIDARIA',
    description = 'Multi-compradores / co-deudores y porcentajes en Historia de Dominio'
WHERE product_code = 'VIV_TERMINADA_SOLID';

UPDATE app.product_prompt_map SET prompt_key = 'PROMPT_VIV_COMPANIA',
    description = 'RUC, Nombramiento RL y Acta de Junta que autoriza la venta'
WHERE product_code = 'VIV_TERMINADA_COMPANIA';

UPDATE app.product_prompt_map SET prompt_key = 'PROMPT_SUSTITUCION',
    description = 'Liquidación deuda banco acreedor anterior + CHD'
WHERE product_code = 'SUSTITUCION_HIPOTECA';

UPDATE app.product_prompt_map SET prompt_key = 'PROMPT_TERRENO_VIVIENDA',
    description = 'Uso de suelo, terreno vs construcción y avalúo desglosado'
WHERE product_code = 'TERRENO_Y_VIVIENDA';
