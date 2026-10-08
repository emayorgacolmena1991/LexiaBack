-- OCR de coactivas puede cerrar el expediente como digitalización ilegible.

DO $$
DECLARE
  nombre text;
BEGIN
  FOR nombre IN
    SELECT con.conname
    FROM pg_constraint con
    JOIN pg_class rel ON rel.oid = con.conrelid
    JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace
    WHERE nsp.nspname = 'app'
      AND rel.relname = 'coactiva_expediente'
      AND con.contype = 'c'
      AND pg_get_constraintdef(con.oid) LIKE '%estado_analisis%'
  LOOP
    EXECUTE format('ALTER TABLE app.coactiva_expediente DROP CONSTRAINT %I', nombre);
  END LOOP;
END $$;

ALTER TABLE app.coactiva_expediente
    ALTER COLUMN estado_analisis TYPE VARCHAR(32);

ALTER TABLE app.coactiva_expediente
    ADD CONSTRAINT coactiva_expediente_estado_analisis_check
        CHECK (estado_analisis IN (
            'NO_APLICA',
            'ANALIZANDO',
            'ANALIZADO',
            'ERROR_ANALISIS',
            'ERROR_CALIDAD_DIGITALIZACION'));
