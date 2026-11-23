ALTER TABLE app.jobs ADD COLUMN IF NOT EXISTS worker_id VARCHAR(100);
ALTER TABLE app.jobs ADD COLUMN IF NOT EXISTS locked_until TIMESTAMPTZ;

INSERT INTO app.escrituracion_prompt_catalog
    (canton, product_code, tipo_documento, system_prompt, activo)
SELECT v.canton, v.product_code, '*',
    'Eres un sistema experto en validación y cotejo notarial en el cantón '
        || '$' || '{canton}, producto ' || '$' || '{productCode} (BIESS o BanEcuador). '
        || 'Recibirás el texto OCR dentro de <expediente_ocr>. '
        || 'Compara identidad (cédula/papeleta) e inmueble (avalúo/historia de dominio). '
        || 'Si el producto es de BIESS o BanEcuador, verifica gravamen, hipoteca y las partes del crédito. '
        || 'Genera observaciones si hay discrepancias. '
        || 'EJECUTA EN ESTA ÚNICA LLAMADA: '
        || '1. EXTRAER comprador, vendedor e inmueble (nombres, cedula, estadoCivil, claveCatastral, linderos, avaluo). '
        || '2. DICTAMEN estado APPROVED | WITH_OBSERVATIONS | REJECTED, resumen y observaciones (codigo, severidad HIGH|MEDIUM|LOW, mensaje). '
        || 'Responde ÚNICAMENTE invocando la herramienta procesar_expediente_completo.',
    true
FROM (VALUES
    ('GUAYAQUIL', 'VIV_HIPOTECADA_BIESS'),
    ('GUAYAQUIL', 'BANECEUADOR'),
    ('*', 'VIV_HIPOTECADA_BIESS'),
    ('*', 'VIV_TERMINADA_IND'),
    ('*', 'VIV_TERMINADA_PREF'),
    ('*', 'VIV_TERMINADA_SOLID'),
    ('*', 'VIV_TERMINADA_COMPANIA'),
    ('*', 'SUSTITUCION_HIPOTECA'),
    ('*', 'TERRENO_Y_VIVIENDA'),
    ('*', 'BANECEUADOR')
) AS v(canton, product_code)
ON CONFLICT (canton, product_code, tipo_documento) DO NOTHING;
