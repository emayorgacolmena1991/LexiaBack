-- Limpia datos de Coactivas tenant por tenant (lexia_app + RLS).
-- No DROP/TRUNCATE. No toca catálogo ni Escrituración.

\set ON_ERROR_STOP on

DO $$
DECLARE
  t RECORD;
  n_exp bigint;
  n_acta bigint;
  n_arch bigint;
  shas text[];
BEGIN
  FOR t IN SELECT id, code FROM control.tenant ORDER BY code LOOP
    PERFORM set_config('app.current_tenant_id', t.id::text, true);

    SELECT count(*) INTO n_exp FROM app.coactiva_expediente;
    SELECT count(*) INTO n_acta FROM app.coactiva_acta;
    SELECT count(*) INTO n_arch FROM app.coactiva_archivo;

    IF n_exp = 0 AND n_acta = 0 AND n_arch = 0 THEN
      RAISE NOTICE 'tenant % vacío — skip', t.code;
      CONTINUE;
    END IF;

    RAISE NOTICE 'tenant % — borrando exp=% acta=% arch=%', t.code, n_exp, n_acta, n_arch;

    SELECT array_agg(DISTINCT sha256) INTO shas
    FROM app.coactiva_archivo
    WHERE sha256 IS NOT NULL AND btrim(sha256) <> '';

    UPDATE app.coactiva_expediente
    SET analisis_archivo_id = NULL, acta_entrega_id = NULL;

    UPDATE app.coactiva_acta SET archivo_id = NULL;

    UPDATE app.coactiva_archivo
    SET expediente_id = NULL, acta_id = NULL;

    UPDATE app.coactiva_acta_item SET expediente_id = NULL;

    UPDATE app.coactiva_analisis SET analisis_padre_id = NULL;

    UPDATE app.coactiva_evento SET archivo_id = NULL;

    UPDATE app.coactiva_notificacion SET archivo_id = NULL;

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
    DELETE FROM app.coactiva_expediente;
    DELETE FROM app.coactiva_archivo;
    DELETE FROM app.coactiva_acta;
    DELETE FROM app.coactiva_embargo_lote;

    DELETE FROM app.jobs
    WHERE tipo IN ('COACTIVA_DIAGNOSTICO', 'COACTIVA_VALIDACION_ANEXO')
      AND tenant_id = t.id;

    IF shas IS NOT NULL THEN
      DELETE FROM app.documento_cache
      WHERE version_prompt = 'coactiva-ocr-v1'
        AND tenant_id = t.id
        AND sha256 = ANY (shas);
    END IF;

    SELECT count(*) INTO n_exp FROM app.coactiva_expediente;
    SELECT count(*) INTO n_acta FROM app.coactiva_acta;
    SELECT count(*) INTO n_arch FROM app.coactiva_archivo;
    IF n_exp <> 0 OR n_acta <> 0 OR n_arch <> 0 THEN
      RAISE EXCEPTION 'tenant % quedó con filas: exp=% acta=% arch=%', t.code, n_exp, n_acta, n_arch;
    END IF;

    RAISE NOTICE 'tenant % — limpio', t.code;
  END LOOP;
END $$;
