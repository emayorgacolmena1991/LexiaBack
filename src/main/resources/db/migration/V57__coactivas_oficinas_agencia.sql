-- El nombre de PORTOVIEJO quedó con el de un delegado ("Johao Perez") y el combo de oficina lo mostraba.
-- Bahía, Calceta y Rocafuerte son agencias de la columna OFICINA; no son la UEC.

UPDATE app.coactiva_oficina
SET nombre = 'Portoviejo'
WHERE codigo = 'PORTOVIEJO'
  AND upper(nombre) NOT LIKE '%PORTOVIEJO%';

INSERT INTO app.coactiva_oficina (tenant_id, codigo, nombre, provincia, sort_order)
SELECT t.id, v.codigo, v.nombre, v.provincia, v.sort_order
FROM control.tenant t
CROSS JOIN (
    VALUES
        ('BAHIA', 'Bahía', 'Manabí', 7),
        ('CALCETA', 'Calceta', 'Manabí', 8),
        ('ROCAFUERTE', 'Rocafuerte', 'Manabí', 9)
) AS v(codigo, nombre, provincia, sort_order)
WHERE t.deleted_at IS NULL
ON CONFLICT (tenant_id, codigo) DO NOTHING;
