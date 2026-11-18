-- El worker del outbox EJD corre sin tenant: con RLS en app.outbox_event no veía ninguna fila.
-- Esta función devuelve solo (id, tenant_id) de los pendientes de todos los tenants; el worker
-- fija el tenant de cada evento y el procesado sigue bajo RLS. Mismo patrón que
-- app.active_memberships_for_user (V8).
CREATE OR REPLACE FUNCTION app.outbox_pendientes(p_event_type TEXT, p_limite INT)
RETURNS TABLE (id UUID, tenant_id UUID)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = app, pg_temp
AS $$
    SELECT o.id, o.tenant_id
      FROM app.outbox_event o
     WHERE o.published_at IS NULL
       AND o.event_type = p_event_type
     ORDER BY o.created_at
     LIMIT LEAST(GREATEST(p_limite, 1), 200);
$$;

REVOKE ALL ON FUNCTION app.outbox_pendientes(TEXT, INT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app.outbox_pendientes(TEXT, INT) TO lexia_app;

-- Orden global por antigüedad; ix_outbox_unpublished empieza por tenant_id.
CREATE INDEX IF NOT EXISTS ix_outbox_pendientes_tipo
    ON app.outbox_event (event_type, created_at)
    WHERE published_at IS NULL;

-- La función corre como su dueño (este rol). Si este rol queda sujeto a RLS en la tabla, la
-- función no ve filas: mismo criterio que V96/V101.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM pg_class c
          JOIN pg_namespace n ON n.oid = c.relnamespace
          JOIN pg_roles r ON r.rolname = current_user
         WHERE n.nspname = 'app'
           AND c.relname = 'outbox_event'
           AND (r.rolsuper OR r.rolbypassrls
                OR (c.relowner = r.oid AND NOT c.relforcerowsecurity))
    ) THEN
        RAISE WARNING 'V107: % está sujeto a RLS en app.outbox_event; app.outbox_pendientes no verá eventos.',
            current_user;
    END IF;
END
$$;
