-- Escrituración, fase 6: datos de cierre de la escritura nueva y pagadores de la carta de financiamiento.
-- - cierre_*: escritura nueva (no confundir con fecha_escritura_antecedente / fecha_inscripcion_antecedente
--   de la minuta). Los ingresa el abogado en la ficha; los usa el informe legal final.
-- - carta_pago_*: quién paga cada impuesto en la carta de financiamiento (NULL = sin marcar).
-- Columnas nuevas en app.legal_case: la tabla ya tiene RLS (tenant_isolation); no hay filas que migrar.

ALTER TABLE app.legal_case
    ADD COLUMN IF NOT EXISTS cierre_fecha_escritura   DATE,
    ADD COLUMN IF NOT EXISTS cierre_notaria           VARCHAR(200),
    ADD COLUMN IF NOT EXISTS cierre_fecha_inscripcion DATE,
    ADD COLUMN IF NOT EXISTS cierre_fecha_informe     DATE,
    ADD COLUMN IF NOT EXISTS carta_pago_plusvalia     VARCHAR(12),
    ADD COLUMN IF NOT EXISTS carta_pago_alcabalas     VARCHAR(12),
    ADD COLUMN IF NOT EXISTS carta_pago_obras         VARCHAR(12);

ALTER TABLE app.legal_case DROP CONSTRAINT IF EXISTS ck_legal_case_carta_pago;
ALTER TABLE app.legal_case
    ADD CONSTRAINT ck_legal_case_carta_pago CHECK (
        (carta_pago_plusvalia IS NULL OR carta_pago_plusvalia IN ('VENDEDOR', 'CLIENTE', 'FINANCIADO'))
        AND (carta_pago_alcabalas IS NULL OR carta_pago_alcabalas IN ('VENDEDOR', 'CLIENTE', 'FINANCIADO'))
        AND (carta_pago_obras IS NULL OR carta_pago_obras IN ('VENDEDOR', 'CLIENTE', 'FINANCIADO')));

COMMENT ON COLUMN app.legal_case.cierre_fecha_escritura IS 'Fecha en que se firmó en notaría la escritura nueva (informe legal).';
COMMENT ON COLUMN app.legal_case.cierre_notaria IS 'Notaría de la escritura nueva (informe legal).';
COMMENT ON COLUMN app.legal_case.cierre_fecha_inscripcion IS 'Fecha de la razón de inscripción de la escritura nueva (informe legal).';
COMMENT ON COLUMN app.legal_case.cierre_fecha_informe IS 'Fecha de emisión del informe legal (entrega al BIESS para desembolso).';
COMMENT ON COLUMN app.legal_case.carta_pago_plusvalia IS 'Carta de financiamiento: VENDEDOR, CLIENTE o FINANCIADO.';
COMMENT ON COLUMN app.legal_case.carta_pago_alcabalas IS 'Carta de financiamiento: VENDEDOR, CLIENTE o FINANCIADO.';
COMMENT ON COLUMN app.legal_case.carta_pago_obras IS 'Carta de financiamiento (obras CEM): VENDEDOR, CLIENTE o FINANCIADO.';
