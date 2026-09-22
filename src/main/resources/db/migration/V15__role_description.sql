ALTER TABLE app.role
    ADD COLUMN description VARCHAR(200);

UPDATE app.role SET description = 'Gestión total de la plataforma, usuarios, roles y configuración.'
WHERE tenant_id = 'b1000000-0000-7000-8000-000000000001' AND code = 'ADMIN';

UPDATE app.role SET description = 'Acceso a expedientes, generación de documentos y revisión legal.'
WHERE tenant_id = 'b1000000-0000-7000-8000-000000000001' AND code = 'ABOGADO_SENIOR';

UPDATE app.role SET description = 'Consulta de expedientes y elaboración de documentos.'
WHERE tenant_id = 'b1000000-0000-7000-8000-000000000001' AND code = 'ANALISTA';

UPDATE app.role SET description = 'Acceso de solo lectura a la información de la plataforma.'
WHERE tenant_id = 'b1000000-0000-7000-8000-000000000001' AND code = 'SOLO_LECTURA';
