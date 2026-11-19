-- Agregados de plataforma. SECURITY DEFINER: lexia_app no cruza RLS; el dueno de las tablas si.
CREATE OR REPLACE FUNCTION app.platform_usage_report()
RETURNS jsonb
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = app, control, pg_temp
AS $$
  SELECT jsonb_build_object(
    'organizations', jsonb_build_object(
      'total', (SELECT count(*) FROM control.tenant t WHERE t.deleted_at IS NULL AND t.tenant_kind <> 'PLATFORM'),
      'firms', (SELECT count(*) FROM control.tenant t WHERE t.deleted_at IS NULL AND t.tenant_kind = 'LEGAL_FIRM'),
      'notaries', (SELECT count(*) FROM control.tenant t WHERE t.deleted_at IS NULL AND t.tenant_kind = 'NOTARY'),
      'active', (SELECT count(*) FROM control.tenant t WHERE t.deleted_at IS NULL AND t.tenant_kind <> 'PLATFORM' AND t.status = 'ACTIVE'),
      'suspended', (SELECT count(*) FROM control.tenant t WHERE t.deleted_at IS NULL AND t.tenant_kind <> 'PLATFORM' AND t.status = 'SUSPENDED'),
      'createdLast30Days', (SELECT count(*) FROM control.tenant t WHERE t.deleted_at IS NULL AND t.tenant_kind <> 'PLATFORM' AND t.created_at >= now() - interval '30 days')
    ),
    'cases', jsonb_build_object(
      'total', (SELECT count(*) FROM app.legal_case c WHERE c.deleted_at IS NULL),
      'createdLast30Days', (SELECT count(*) FROM app.legal_case c WHERE c.deleted_at IS NULL AND c.created_at >= now() - interval '30 days')
    ),
    'documents', jsonb_build_object(
      'total', (SELECT count(*) FROM app.document d WHERE d.deleted_at IS NULL),
      'classifiedByAi', (SELECT count(*) FROM app.document d WHERE d.deleted_at IS NULL AND d.clasificacion_origen = 'AUTO'),
      'pendingClassification', (SELECT count(*) FROM app.document d WHERE d.deleted_at IS NULL AND d.clasificacion_estado = 'PENDIENTE')
    ),
    'ai', jsonb_build_object(
      'calls', (SELECT count(*) FROM app.ia_log_consumo),
      'promptTokens', (SELECT coalesce(sum(prompt_tokens), 0)::bigint FROM app.ia_log_consumo),
      'completionTokens', (SELECT coalesce(sum(completion_tokens), 0)::bigint FROM app.ia_log_consumo),
      'callsLast30Days', (SELECT count(*) FROM app.ia_log_consumo WHERE created_at >= now() - interval '30 days')
    ),
    'users', jsonb_build_object(
      'total', (SELECT count(*) FROM app.app_user u WHERE u.deleted_at IS NULL),
      'active', (SELECT count(*) FROM app.app_user u WHERE u.deleted_at IS NULL AND u.status = 'ACTIVE'),
      'locked', (SELECT count(*) FROM app.app_user u WHERE u.deleted_at IS NULL AND u.status = 'LOCKED'),
      'withSessionLast7Days', (
        SELECT count(DISTINCT s.user_id)
        FROM app.user_session s
        WHERE s.created_at >= now() - interval '7 days'
          AND s.revoked_at IS NULL
      )
    ),
    'organizationsByMonth', (
      SELECT coalesce(jsonb_agg(jsonb_build_object('month', to_char(m, 'YYYY-MM'), 'count', c) ORDER BY m), '[]'::jsonb)
      FROM (
        SELECT gs AS m,
               (SELECT count(*) FROM control.tenant t
                 WHERE t.deleted_at IS NULL
                   AND t.tenant_kind <> 'PLATFORM'
                   AND t.created_at >= gs
                   AND t.created_at < gs + interval '1 month') AS c
        FROM generate_series(
          date_trunc('month', now()) - interval '5 months',
          date_trunc('month', now()),
          interval '1 month') gs
      ) months
    ),
    'casesByMonth', (
      SELECT coalesce(jsonb_agg(jsonb_build_object('month', to_char(m, 'YYYY-MM'), 'count', c) ORDER BY m), '[]'::jsonb)
      FROM (
        SELECT gs AS m,
               (SELECT count(*) FROM app.legal_case c
                 WHERE c.deleted_at IS NULL
                   AND c.created_at >= gs
                   AND c.created_at < gs + interval '1 month') AS c
        FROM generate_series(
          date_trunc('month', now()) - interval '5 months',
          date_trunc('month', now()),
          interval '1 month') gs
      ) months
    )
  );
$$;

REVOKE ALL ON FUNCTION app.platform_usage_report() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app.platform_usage_report() TO lexia_app;