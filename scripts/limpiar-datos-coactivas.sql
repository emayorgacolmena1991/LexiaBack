-- =============================================================================
-- Limpiar DATOS de Coactivas (re-subir desde cero)
-- =============================================================================
-- NO hace DROP / TRUNCATE. Solo DELETE / UPDATE.
-- NO toca Escrituración (writing_file, extracted_data, documento_texto_ocr, etc.).
-- NO borra catálogo: oficina, delegado, plantilla, prompt_catalog.
--
-- DBeaver:
--   1) Ejecutá primero el bloque "0. Conteo" (seleccioná solo esas líneas → Execute).
--   2) Revisá los números.
--   3) Ejecutá el bloque "1. Borrado" completo (BEGIN … COMMIT).
--   4) Si algo falla, el ROLLBACK deja todo como estaba (si no llegaste al COMMIT).
--
-- RLS: conviene correrlo como rol que bypass RLS (p. ej. owner / superuser) o
--      con app.current_tenant_id() del tenant que querés limpiar.
-- PDFs en disco (data/coactivas): este script NO los borra; solo las filas.
-- legal_case creados como COA-… quedan huérfanos a propósito (no son tablas de
-- Escrituración; borrarlos implica case_stage y más). Ver sección opcional al final.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 0. Conteo (solo lectura)
-- -----------------------------------------------------------------------------
SELECT 'coactiva_expediente' AS tabla, count(*) AS filas FROM app.coactiva_expediente
UNION ALL SELECT 'coactiva_acta', count(*) FROM app.coactiva_acta
UNION ALL SELECT 'coactiva_acta_item', count(*) FROM app.coactiva_acta_item
UNION ALL SELECT 'coactiva_archivo', count(*) FROM app.coactiva_archivo
UNION ALL SELECT 'coactiva_analisis', count(*) FROM app.coactiva_analisis
UNION ALL SELECT 'coactiva_tarea_sugerida', count(*) FROM app.coactiva_tarea_sugerida
UNION ALL SELECT 'coactiva_evento', count(*) FROM app.coactiva_evento
UNION ALL SELECT 'coactiva_participante', count(*) FROM app.coactiva_participante
UNION ALL SELECT 'coactiva_notificacion', count(*) FROM app.coactiva_notificacion
UNION ALL SELECT 'coactiva_etapa_historial', count(*) FROM app.coactiva_etapa_historial
UNION ALL SELECT 'coactiva_actuacion', count(*) FROM app.coactiva_actuacion
UNION ALL SELECT 'coactiva_medida', count(*) FROM app.coactiva_medida
UNION ALL SELECT 'coactiva_solicitud', count(*) FROM app.coactiva_solicitud
UNION ALL SELECT 'coactiva_documento_draft', count(*) FROM app.coactiva_documento_draft
UNION ALL SELECT 'coactiva_embargo_registro', count(*) FROM app.coactiva_embargo_registro
UNION ALL SELECT 'coactiva_embargo_lote', count(*) FROM app.coactiva_embargo_lote
UNION ALL SELECT 'jobs COACTIVA_*', count(*) FROM app.jobs
         WHERE tipo IN ('COACTIVA_DIAGNOSTICO', 'COACTIVA_VALIDACION_ANEXO')
ORDER BY 1;

-- -----------------------------------------------------------------------------
-- 1. Borrado (ejecutar entero)
-- -----------------------------------------------------------------------------
BEGIN;

-- Guarda sha de archivos coactivos para limpiar solo su caché OCR (no la de Escrituración).
CREATE TEMP TABLE tmp_coa_sha ON COMMIT DROP AS
SELECT DISTINCT sha256
FROM app.coactiva_archivo
WHERE sha256 IS NOT NULL AND btrim(sha256) <> '';

CREATE TEMP TABLE tmp_coa_case ON COMMIT DROP AS
SELECT DISTINCT case_id
FROM app.coactiva_expediente
WHERE case_id IS NOT NULL;

-- 1.1 Romper ciclos de FK (expediente ↔ acta ↔ archivo)
UPDATE app.coactiva_expediente
SET analisis_archivo_id = NULL,
    acta_entrega_id = NULL;

UPDATE app.coactiva_acta
SET archivo_id = NULL;

UPDATE app.coactiva_archivo
SET expediente_id = NULL,
    acta_id = NULL;

UPDATE app.coactiva_acta_item
SET expediente_id = NULL;

UPDATE app.coactiva_analisis
SET analisis_padre_id = NULL;

UPDATE app.coactiva_evento
SET archivo_id = NULL;

UPDATE app.coactiva_notificacion
SET archivo_id = NULL;

-- 1.2 Hijas que apuntan a análisis / expediente / archivo
DELETE FROM app.coactiva_tarea_sugerida;
DELETE FROM app.coactiva_analisis;

DELETE FROM app.coactiva_embargo_registro;
DELETE FROM app.coactiva_documento_draft;
DELETE FROM app.coactiva_actuacion;
DELETE FROM app.coactiva_medida;
DELETE FROM app.coactiva_solicitud;

DELETE FROM app.coactiva_notificacion;
DELETE FROM app.coactiva_participante;
DELETE FROM app.coactiva_etapa_historial;
DELETE FROM app.coactiva_evento;

DELETE FROM app.coactiva_acta_item;

-- 1.3 Expedientes
DELETE FROM app.coactiva_expediente;

-- 1.4 Archivos (vinculados, sin asignar, actas, unificados, actuaciones)
DELETE FROM app.coactiva_archivo;

-- 1.5 Actas
DELETE FROM app.coactiva_acta;

-- 1.6 Lotes de embargo vacíos (ya sin registros)
DELETE FROM app.coactiva_embargo_lote;

-- 1.7 Cola de jobs solo de Coactivas (no ESCRITURACION_*)
DELETE FROM app.jobs
WHERE tipo IN ('COACTIVA_DIAGNOSTICO', 'COACTIVA_VALIDACION_ANEXO');

-- 1.8 Caché OCR de esos SHA (compartida con Escrituración: solo filas de esos hash)
DELETE FROM app.documento_cache dc
USING tmp_coa_sha t
WHERE dc.sha256 = t.sha256
  AND dc.version_prompt = 'coactiva-ocr-v1';

-- Verificación rápida dentro de la misma transacción
DO $$
DECLARE
  n_exp bigint;
  n_acta bigint;
  n_arch bigint;
BEGIN
  SELECT count(*) INTO n_exp FROM app.coactiva_expediente;
  SELECT count(*) INTO n_acta FROM app.coactiva_acta;
  SELECT count(*) INTO n_arch FROM app.coactiva_archivo;
  IF n_exp <> 0 OR n_acta <> 0 OR n_arch <> 0 THEN
    RAISE EXCEPTION 'Quedaron filas: expediente=%, acta=%, archivo=%', n_exp, n_acta, n_arch;
  END IF;
END $$;

COMMIT;

-- -----------------------------------------------------------------------------
-- 2. Opcional: legal_case huérfanos COA (NO ejecutar si no lo necesitás)
-- -----------------------------------------------------------------------------
-- Descomentar solo si querés borrar también el caso legal creado al generar
-- expedientes. Tocá solo cases cuyo id estaba en coactiva_expediente.
--
-- BEGIN;
-- -- Ajustá nombres reales de tablas hijas de legal_case en tu BD antes de correr.
-- -- Ejemplo mínimo (revisar FKs en DBeaver → ER Diagram):
-- -- DELETE FROM app.case_note WHERE case_id IN (SELECT case_id FROM tmp_coa_case); -- tmp ya no existe post-COMMIT
-- -- Mejor: guardar case_ids en una tabla permanente temporal antes del COMMIT.
-- COMMIT;

-- Tras el COMMIT, volvé a correr el bloque 0 (conteo) para confirmar ceros.
