-- Copia el maestro de escrituración y coactivas (catálogos, matriz, proceso y reglas)
-- desde Tenant Demo hacia un bufete o notaría. Idempotente: no pisa lo que el tenant ya tenga.
-- En la base original ese maestro solo estaba en b1000000-0000-7000-8000-000000000001.

CREATE OR REPLACE FUNCTION app.seed_tenant_masters(p_target uuid)
RETURNS integer
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = app, control, pg_temp
AS $$
DECLARE
    v_source uuid := 'b1000000-0000-7000-8000-000000000001';
    v_inserted integer := 0;
    v_rows integer := 0;
BEGIN
    IF p_target IS NULL OR p_target = v_source THEN
        RETURN 0;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM control.tenant
        WHERE id = p_target AND deleted_at IS NULL AND tenant_kind IN ('LEGAL_FIRM', 'NOTARY')
    ) THEN
        RETURN 0;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM control.tenant WHERE id = v_source AND deleted_at IS NULL
    ) THEN
        RAISE EXCEPTION 'No existe el tenant maestro de catálogos';
    END IF;

    CREATE TEMP TABLE IF NOT EXISTS seed_map_catalog (src uuid PRIMARY KEY, dst uuid NOT NULL) ON COMMIT DROP;
    CREATE TEMP TABLE IF NOT EXISTS seed_map_item (src uuid PRIMARY KEY, dst uuid NOT NULL) ON COMMIT DROP;
    CREATE TEMP TABLE IF NOT EXISTS seed_map_process (src uuid PRIMARY KEY, dst uuid NOT NULL) ON COMMIT DROP;
    CREATE TEMP TABLE IF NOT EXISTS seed_map_rule (src uuid PRIMARY KEY, dst uuid NOT NULL) ON COMMIT DROP;
    TRUNCATE seed_map_catalog, seed_map_item, seed_map_process, seed_map_rule;

    INSERT INTO app.catalog (tenant_id, code, name)
    SELECT p_target, c.code, c.name
    FROM app.catalog c
    WHERE c.tenant_id = v_source AND c.deleted_at IS NULL
      AND NOT EXISTS (
          SELECT 1 FROM app.catalog x
          WHERE x.tenant_id = p_target AND x.code = c.code AND x.deleted_at IS NULL
      );
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    v_inserted := v_inserted + v_rows;

    INSERT INTO seed_map_catalog (src, dst)
    SELECT s.id, d.id
    FROM app.catalog s
    JOIN app.catalog d ON d.tenant_id = p_target AND d.code = s.code AND d.deleted_at IS NULL
    WHERE s.tenant_id = v_source AND s.deleted_at IS NULL;

    INSERT INTO app.catalog_item (tenant_id, catalog_id, code, label, sort_order, active)
    SELECT p_target, m.dst, i.code, i.label, i.sort_order, i.active
    FROM app.catalog_item i
    JOIN seed_map_catalog m ON m.src = i.catalog_id
    WHERE i.tenant_id = v_source
      AND NOT EXISTS (
          SELECT 1 FROM app.catalog_item x WHERE x.catalog_id = m.dst AND x.code = i.code
      );
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    v_inserted := v_inserted + v_rows;

    INSERT INTO seed_map_item (src, dst)
    SELECT s.id, d.id
    FROM app.catalog_item s
    JOIN app.catalog sc ON sc.id = s.catalog_id
    JOIN app.catalog dc ON dc.tenant_id = p_target AND dc.code = sc.code AND dc.deleted_at IS NULL
    JOIN app.catalog_item d ON d.catalog_id = dc.id AND d.code = s.code
    WHERE s.tenant_id = v_source;

    INSERT INTO app.act_requirement (tenant_id, act_item_id, document_item_id, required, description, sort_order)
    SELECT p_target, ma.dst, md.dst, a.required, a.description, a.sort_order
    FROM app.act_requirement a
    JOIN seed_map_item ma ON ma.src = a.act_item_id
    JOIN seed_map_item md ON md.src = a.document_item_id
    WHERE a.tenant_id = v_source
      AND NOT EXISTS (
          SELECT 1 FROM app.act_requirement x
          WHERE x.act_item_id = ma.dst AND x.document_item_id = md.dst
      );

    INSERT INTO app.document_requirement (
        tenant_id, product_code, document_type_code, is_mandatory, max_validity_days, canton, description, sort_order)
    SELECT p_target, r.product_code, r.document_type_code, r.is_mandatory, r.max_validity_days,
           r.canton, r.description, r.sort_order
    FROM app.document_requirement r
    WHERE r.tenant_id = v_source
    ON CONFLICT (tenant_id, product_code, document_type_code, canton) DO NOTHING;

    INSERT INTO app.product_template (
        tenant_id, product_code, template_kind, label, storage_key, company_supplies_cv, active, sort_order)
    SELECT p_target, t.product_code, t.template_kind, t.label, t.storage_key,
           t.company_supplies_cv, t.active, t.sort_order
    FROM app.product_template t
    WHERE t.tenant_id = v_source
    ON CONFLICT (tenant_id, product_code, template_kind) DO NOTHING;

    INSERT INTO app.ejd_operation_document_req (
        tenant_id, operation_code, document_type_code, sort_order, deprecated_at)
    SELECT p_target, r.operation_code, r.document_type_code, r.sort_order, r.deprecated_at
    FROM app.ejd_operation_document_req r
    WHERE r.tenant_id = v_source
    ON CONFLICT (tenant_id, operation_code, document_type_code) DO NOTHING;

    INSERT INTO app.integration (tenant_id, code, name, enabled)
    SELECT p_target, i.code, i.name, i.enabled
    FROM app.integration i
    WHERE i.tenant_id = v_source
    ON CONFLICT (tenant_id, code) DO NOTHING;

    INSERT INTO app.ejd_stage_gate_req (tenant_id, stage_code, gate_code, sort_order)
    SELECT p_target, g.stage_code, g.gate_code, g.sort_order
    FROM app.ejd_stage_gate_req g
    WHERE g.tenant_id = v_source
    ON CONFLICT (tenant_id, stage_code, gate_code) DO NOTHING;

    INSERT INTO app.ejd_stage_integration (tenant_id, stage_code, integration_code, sort_order)
    SELECT p_target, g.stage_code, g.integration_code, g.sort_order
    FROM app.ejd_stage_integration g
    WHERE g.tenant_id = v_source
    ON CONFLICT (tenant_id, stage_code, integration_code) DO NOTHING;

    INSERT INTO app.rule_def (tenant_id, code, version, name, body, status)
    SELECT p_target, r.code, r.version, r.name, r.body, r.status
    FROM app.rule_def r
    WHERE r.tenant_id = v_source
      AND NOT EXISTS (
          SELECT 1 FROM app.rule_def x
          WHERE x.tenant_id = p_target AND x.code = r.code AND x.version = r.version
      );

    INSERT INTO seed_map_rule (src, dst)
    SELECT s.id, d.id
    FROM app.rule_def s
    JOIN app.rule_def d ON d.tenant_id = p_target AND d.code = s.code AND d.version = s.version
    WHERE s.tenant_id = v_source;

    INSERT INTO app.process_definition (tenant_id, code, name, case_type, config_version, last_published_at)
    SELECT p_target, p.code, p.name, p.case_type, p.config_version, p.last_published_at
    FROM app.process_definition p
    WHERE p.tenant_id = v_source AND p.deleted_at IS NULL
      AND NOT EXISTS (
          SELECT 1 FROM app.process_definition x
          WHERE x.tenant_id = p_target AND x.code = p.code AND x.deleted_at IS NULL
      );

    INSERT INTO seed_map_process (src, dst)
    SELECT s.id, d.id
    FROM app.process_definition s
    JOIN app.process_definition d ON d.tenant_id = p_target AND d.code = s.code AND d.deleted_at IS NULL
    WHERE s.tenant_id = v_source AND s.deleted_at IS NULL;

    INSERT INTO app.process_stage_def (
        tenant_id, process_definition_id, code, label, short_label, sort_order,
        sla_hours, color_key, active, owner_actor, deprecated_at)
    SELECT p_target, mp.dst, s.code, s.label, s.short_label, s.sort_order,
           s.sla_hours, s.color_key, s.active, s.owner_actor, s.deprecated_at
    FROM app.process_stage_def s
    JOIN seed_map_process mp ON mp.src = s.process_definition_id
    WHERE s.tenant_id = v_source
      AND NOT EXISTS (
          SELECT 1 FROM app.process_stage_def x
          WHERE x.process_definition_id = mp.dst AND x.code = s.code
      );

    INSERT INTO app.gate_def (
        tenant_id, process_definition_id, code, question, sort_order, response_type,
        continue_criterion, mandatory, message_ok, message_fail, active)
    SELECT p_target, mp.dst, g.code, g.question, g.sort_order, g.response_type,
           g.continue_criterion, g.mandatory, g.message_ok, g.message_fail, g.active
    FROM app.gate_def g
    JOIN seed_map_process mp ON mp.src = g.process_definition_id
    WHERE g.tenant_id = v_source
      AND NOT EXISTS (
          SELECT 1 FROM app.gate_def x
          WHERE x.process_definition_id = mp.dst AND x.code = g.code
      );

    INSERT INTO app.validation_def (
        tenant_id, process_definition_id, code, label, sort_order, rule_def_id, description, active)
    SELECT p_target, mp.dst, v.code, v.label, v.sort_order, mr.dst, v.description, v.active
    FROM app.validation_def v
    JOIN seed_map_process mp ON mp.src = v.process_definition_id
    LEFT JOIN seed_map_rule mr ON mr.src = v.rule_def_id
    WHERE v.tenant_id = v_source
      AND NOT EXISTS (
          SELECT 1 FROM app.validation_def x
          WHERE x.process_definition_id = mp.dst AND x.code = v.code
      );

    RETURN v_inserted;
END;
$$;

REVOKE ALL ON FUNCTION app.seed_tenant_masters(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app.seed_tenant_masters(uuid) TO lexia_app;

SELECT app.seed_tenant_masters(id)
FROM control.tenant
WHERE deleted_at IS NULL
  AND tenant_kind IN ('LEGAL_FIRM', 'NOTARY');