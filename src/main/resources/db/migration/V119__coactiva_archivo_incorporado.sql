-- Actuaciones generadas que la abogada agregó al expediente (botón "Agregar al expediente").
-- Solo aplica a archivos tipo ACTUACION: el expediente completo incluye una actuación cuando
-- incorporado_at no es nulo. El resto de tipos se incluye siempre.

ALTER TABLE app.coactiva_archivo
    ADD COLUMN IF NOT EXISTS incorporado_at  TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS incorporado_por UUID;

COMMENT ON COLUMN app.coactiva_archivo.incorporado_at IS
    'Cuándo se agregó la actuación al expediente completo (null = aún no agregada).';
