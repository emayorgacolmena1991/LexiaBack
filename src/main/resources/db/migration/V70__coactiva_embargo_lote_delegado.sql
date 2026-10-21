-- Data 2 pasa de un lote EN_PREPARACION por tenant a uno por tenant + delegado.
-- El identificador es app.coactiva_delegado.id (el mismo de coactiva_expediente.delegado_id).
--
-- Lotes ya existentes: se les asigna delegado solo cuando TODOS sus registros apuntan a
-- expedientes con el mismo delegado_id no nulo y ese delegado sigue en el catálogo.
-- Si el lote no tiene registros, mezcla delegados, o algún expediente no tiene delegado,
-- queda con delegado_id NULL. No se inventa una asignación. Esos lotes siguen en la tabla
-- (históricos legibles) y no participan del lote activo de ningún delegado.

ALTER TABLE app.coactiva_embargo_lote
    ADD COLUMN delegado_id UUID,
    ADD COLUMN delegado_nombre VARCHAR(200);

ALTER TABLE app.coactiva_embargo_lote
    ADD CONSTRAINT fk_coactiva_embargo_lote_delegado
        FOREIGN KEY (delegado_id, tenant_id)
        REFERENCES app.coactiva_delegado (id, tenant_id);

UPDATE app.coactiva_embargo_lote l
SET delegado_id = u.delegado_id,
    delegado_nombre = u.nombre
FROM (
    SELECT r.lote_id,
           MIN(e.delegado_id::text)::uuid AS delegado_id,
           MIN(d.nombre) AS nombre
    FROM app.coactiva_embargo_registro r
    JOIN app.coactiva_expediente e
        ON e.id = r.expediente_id AND e.tenant_id = r.tenant_id
    LEFT JOIN app.coactiva_delegado d
        ON d.id = e.delegado_id AND d.tenant_id = e.tenant_id AND d.deleted_at IS NULL
    GROUP BY r.lote_id
    HAVING COUNT(*) > 0
       AND COUNT(e.delegado_id) = COUNT(*)
       AND COUNT(DISTINCT e.delegado_id) = 1
       AND COUNT(d.id) = COUNT(*)
) u
WHERE l.id = u.lote_id;

DROP INDEX app.ux_coactiva_embargo_lote_activo;

CREATE UNIQUE INDEX ux_coactiva_embargo_lote_activo
    ON app.coactiva_embargo_lote (tenant_id, delegado_id)
    WHERE estado = 'EN_PREPARACION';
