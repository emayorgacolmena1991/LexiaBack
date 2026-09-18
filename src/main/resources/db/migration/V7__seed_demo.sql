-- Semilla Demo. No es derecho vigente ni credencial productiva.

INSERT INTO control.platform_role (id, code, name) VALUES
    ('a1000000-0000-7000-8000-000000000001', 'SUPER_ADMIN', 'Super administrador LEXIA'),
    ('a1000000-0000-7000-8000-000000000002', 'SUPPORT', 'Soporte de plataforma');

INSERT INTO control.platform_user (id, email, display_name, status, platform_role_id) VALUES
    ('a2000000-0000-7000-8000-000000000001', 'plataforma@lexia.demo', 'Operador Demo', 'ACTIVE',
     'a1000000-0000-7000-8000-000000000001');

INSERT INTO control.tenant (id, code, name, status, timezone, isolation_mode, plan_code, max_users, max_cases)
VALUES (
    'b1000000-0000-7000-8000-000000000001',
    'tenant-demo',
    'Tenant Demo',
    'ACTIVE',
    'America/Bogota',
    'SHARED',
    'ENTERPRISE_DEMO',
    50,
    1000
);

INSERT INTO control.tenant_module (tenant_id, module_code, enabled)
SELECT 'b1000000-0000-7000-8000-000000000001', m, TRUE
FROM unnest(ARRAY['OPERACION_LEGAL', 'NOTARIA', 'COMPLIANCE', 'LITIGIO', 'CONSULTORIA']) AS m;

INSERT INTO app.legal_entity (id, tenant_id, nit, legal_name, trade_name, entity_type, domicile, status)
VALUES (
    'c1000000-0000-7000-8000-000000000001',
    'b1000000-0000-7000-8000-000000000001',
    '900000001-1',
    'Firma Jurídica Demo S.A.S.',
    'Lexia Demo',
    'COMPANY',
    'Bogotá D.C.',
    'ACTIVE'
);

INSERT INTO app.app_user (id, email, display_name, status)
VALUES (
    'd1000000-0000-7000-8000-000000000001',
    'laura.gomez@lexia.demo',
    'Laura Gómez',
    'ACTIVE'
);

-- Hash placeholder: no es una contraseña usable. La API de login no existe en esta fase.
INSERT INTO app.user_credential (user_id, password_hash, algorithm, must_change)
VALUES (
    'd1000000-0000-7000-8000-000000000001',
    'argon2id$demo-not-a-real-hash',
    'argon2id',
    TRUE
);

INSERT INTO app.membership (id, user_id, tenant_id, legal_entity_id, status)
VALUES (
    'e1000000-0000-7000-8000-000000000001',
    'd1000000-0000-7000-8000-000000000001',
    'b1000000-0000-7000-8000-000000000001',
    'c1000000-0000-7000-8000-000000000001',
    'ACTIVE'
);

INSERT INTO app.permission (id, code, name, module_code) VALUES
    ('f1000000-0000-7000-8000-000000000001', 'admin:tenant:leer', 'Ver tenant', 'admin'),
    ('f1000000-0000-7000-8000-000000000002', 'admin:usuarios:invitar', 'Invitar usuarios', 'admin'),
    ('f1000000-0000-7000-8000-000000000003', 'admin:roles:escribir', 'Mantener roles', 'admin'),
    ('f1000000-0000-7000-8000-000000000004', 'admin:catalogos:escribir', 'Mantener catálogos', 'admin'),
    ('f1000000-0000-7000-8000-000000000005', 'admin:integraciones:escribir', 'Mantener integraciones', 'admin'),
    ('f1000000-0000-7000-8000-000000000006', 'expedientes:caso:leer', 'Leer expedientes', 'expedientes'),
    ('f1000000-0000-7000-8000-000000000007', 'expedientes:caso:escribir', 'Escribir expedientes', 'expedientes'),
    ('f1000000-0000-7000-8000-000000000008', 'expedientes:caso:asignar', 'Asignar expedientes', 'expedientes'),
    ('f1000000-0000-7000-8000-000000000009', 'documentos:archivo:leer', 'Leer documentos', 'documentos'),
    ('f1000000-0000-7000-8000-00000000000a', 'documentos:archivo:cargar', 'Cargar documentos', 'documentos'),
    ('f1000000-0000-7000-8000-00000000000b', 'tareas:item:escribir', 'Escribir tareas', 'tareas'),
    ('f1000000-0000-7000-8000-00000000000c', 'excepciones:item:resolver', 'Resolver excepciones', 'excepciones'),
    ('f1000000-0000-7000-8000-00000000000d', 'revision:decision:registrar', 'Registrar decisión humana', 'revision'),
    ('f1000000-0000-7000-8000-00000000000e', 'auditoria:evento:leer', 'Leer auditoría', 'auditoria');

INSERT INTO app.role (id, tenant_id, code, name, is_system) VALUES
    ('f2000000-0000-7000-8000-000000000001', 'b1000000-0000-7000-8000-000000000001', 'ADMIN', 'Administrador', TRUE),
    ('f2000000-0000-7000-8000-000000000002', 'b1000000-0000-7000-8000-000000000001', 'ABOGADO_SENIOR', 'Abogado senior', TRUE),
    ('f2000000-0000-7000-8000-000000000003', 'b1000000-0000-7000-8000-000000000001', 'ANALISTA', 'Analista', TRUE),
    ('f2000000-0000-7000-8000-000000000004', 'b1000000-0000-7000-8000-000000000001', 'SOLO_LECTURA', 'Solo lectura', TRUE);

INSERT INTO app.role_permission (role_id, permission_id, tenant_id)
SELECT 'f2000000-0000-7000-8000-000000000001', id, 'b1000000-0000-7000-8000-000000000001'
FROM app.permission;

INSERT INTO app.role_permission (role_id, permission_id, tenant_id)
SELECT 'f2000000-0000-7000-8000-000000000002', id, 'b1000000-0000-7000-8000-000000000001'
FROM app.permission
WHERE code IN (
    'admin:usuarios:invitar',
    'expedientes:caso:leer', 'expedientes:caso:escribir', 'expedientes:caso:asignar',
    'documentos:archivo:leer', 'documentos:archivo:cargar',
    'tareas:item:escribir', 'excepciones:item:resolver',
    'revision:decision:registrar', 'auditoria:evento:leer'
);

INSERT INTO app.role_permission (role_id, permission_id, tenant_id)
SELECT 'f2000000-0000-7000-8000-000000000003', id, 'b1000000-0000-7000-8000-000000000001'
FROM app.permission
WHERE code IN (
    'expedientes:caso:leer', 'documentos:archivo:leer',
    'tareas:item:escribir', 'auditoria:evento:leer'
);

INSERT INTO app.role_permission (role_id, permission_id, tenant_id)
SELECT 'f2000000-0000-7000-8000-000000000004', id, 'b1000000-0000-7000-8000-000000000001'
FROM app.permission
WHERE code IN ('expedientes:caso:leer', 'documentos:archivo:leer', 'auditoria:evento:leer');

INSERT INTO app.membership_role (membership_id, role_id, tenant_id) VALUES
    ('e1000000-0000-7000-8000-000000000001',
     'f2000000-0000-7000-8000-000000000002',
     'b1000000-0000-7000-8000-000000000001');

INSERT INTO app.tenant_parameter (tenant_id, param_key, param_value) VALUES
    ('b1000000-0000-7000-8000-000000000001', 'timezone', 'America/Bogota'),
    ('b1000000-0000-7000-8000-000000000001', 'case_code_pattern', 'LEX-YYYY-###'),
    ('b1000000-0000-7000-8000-000000000001', 'sla_default_hours', '72');

INSERT INTO app.catalog (id, tenant_id, code, name) VALUES
    ('f3000000-0000-7000-8000-000000000001', 'b1000000-0000-7000-8000-000000000001', 'CASE_TYPE', 'Tipos de proceso'),
    ('f3000000-0000-7000-8000-000000000002', 'b1000000-0000-7000-8000-000000000001', 'DOC_TYPE', 'Tipos de documento');

INSERT INTO app.catalog_item (tenant_id, catalog_id, code, label, sort_order) VALUES
    ('b1000000-0000-7000-8000-000000000001', 'f3000000-0000-7000-8000-000000000001', 'EJD', 'Escrituración', 1),
    ('b1000000-0000-7000-8000-000000000001', 'f3000000-0000-7000-8000-000000000001', 'ECD', 'Coactivas', 2),
    ('b1000000-0000-7000-8000-000000000001', 'f3000000-0000-7000-8000-000000000002', 'CERT_TRADICION', 'Certificado de tradición', 1),
    ('b1000000-0000-7000-8000-000000000001', 'f3000000-0000-7000-8000-000000000002', 'MINUTA', 'Minuta', 2);

INSERT INTO app.process_definition (id, tenant_id, code, name, case_type) VALUES
    ('f4000000-0000-7000-8000-000000000001', 'b1000000-0000-7000-8000-000000000001', 'EJD', 'Escrituración E1–E8', 'EJD'),
    ('f4000000-0000-7000-8000-000000000002', 'b1000000-0000-7000-8000-000000000001', 'ECD', 'Coactivas C1–C9', 'ECD');

INSERT INTO app.process_stage_def (tenant_id, process_definition_id, code, label, short_label, sort_order) VALUES
    ('b1000000-0000-7000-8000-000000000001', 'f4000000-0000-7000-8000-000000000001', 'e1', 'Recepción', 'Recepción', 1),
    ('b1000000-0000-7000-8000-000000000001', 'f4000000-0000-7000-8000-000000000001', 'e2', 'Estudio de título', 'Estudio', 2),
    ('b1000000-0000-7000-8000-000000000001', 'f4000000-0000-7000-8000-000000000001', 'e3', 'Observaciones / regularización', 'Observaciones', 3),
    ('b1000000-0000-7000-8000-000000000001', 'f4000000-0000-7000-8000-000000000001', 'e4', 'Preparación jurídica', 'Preparación', 4),
    ('b1000000-0000-7000-8000-000000000001', 'f4000000-0000-7000-8000-000000000001', 'e5', 'Notaría / matrización', 'Notaría', 5),
    ('b1000000-0000-7000-8000-000000000001', 'f4000000-0000-7000-8000-000000000001', 'e6', 'Impuestos municipales', 'Municipio', 6),
    ('b1000000-0000-7000-8000-000000000001', 'f4000000-0000-7000-8000-000000000001', 'e7', 'Cierre de escritura', 'Cierre', 7),
    ('b1000000-0000-7000-8000-000000000001', 'f4000000-0000-7000-8000-000000000001', 'e8', 'Registro / inscripción', 'Registro', 8),
    ('b1000000-0000-7000-8000-000000000001', 'f4000000-0000-7000-8000-000000000002', 'c1', 'Apertura', 'Apertura', 1),
    ('b1000000-0000-7000-8000-000000000001', 'f4000000-0000-7000-8000-000000000002', 'c2', 'Digitalización', 'Digitalización', 2),
    ('b1000000-0000-7000-8000-000000000001', 'f4000000-0000-7000-8000-000000000002', 'c3', 'Validación', 'Validación', 3),
    ('b1000000-0000-7000-8000-000000000001', 'f4000000-0000-7000-8000-000000000002', 'c4', 'Análisis jurídico', 'Análisis', 4),
    ('b1000000-0000-7000-8000-000000000001', 'f4000000-0000-7000-8000-000000000002', 'c5', 'Procedencia', 'Procedencia', 5),
    ('b1000000-0000-7000-8000-000000000001', 'f4000000-0000-7000-8000-000000000002', 'c6', 'Mandamiento', 'Mandamiento', 6),
    ('b1000000-0000-7000-8000-000000000001', 'f4000000-0000-7000-8000-000000000002', 'c7', 'Ejecución / medidas', 'Medidas', 7),
    ('b1000000-0000-7000-8000-000000000001', 'f4000000-0000-7000-8000-000000000002', 'c8', 'Seguimiento', 'Seguimiento', 8),
    ('b1000000-0000-7000-8000-000000000001', 'f4000000-0000-7000-8000-000000000002', 'c9', 'Cierre', 'Cierre', 9);

INSERT INTO app.gate_def (tenant_id, process_definition_id, code, question, sort_order) VALUES
    ('b1000000-0000-7000-8000-000000000001', 'f4000000-0000-7000-8000-000000000001', 'G-001', '¿Identidad de las partes verificada? (estructura Demo)', 1),
    ('b1000000-0000-7000-8000-000000000001', 'f4000000-0000-7000-8000-000000000001', 'G-002', '¿Estudio de título listo para revisión humana? (estructura Demo)', 2),
    ('b1000000-0000-7000-8000-000000000001', 'f4000000-0000-7000-8000-000000000002', 'CO-G-001', '¿Título ejecutivo presente en el expediente? (estructura Demo)', 1),
    ('b1000000-0000-7000-8000-000000000001', 'f4000000-0000-7000-8000-000000000002', 'CO-G-002', '¿La acción coactiva procede a revisión humana? (estructura Demo)', 2);

INSERT INTO app.validation_def (tenant_id, process_definition_id, code, label, sort_order) VALUES
    ('b1000000-0000-7000-8000-000000000001', 'f4000000-0000-7000-8000-000000000001', 'V1', 'Presencia documental', 1),
    ('b1000000-0000-7000-8000-000000000001', 'f4000000-0000-7000-8000-000000000001', 'V2', 'Coherencia de identificación', 2),
    ('b1000000-0000-7000-8000-000000000001', 'f4000000-0000-7000-8000-000000000001', 'V3', 'Vigencia de certificados', 3),
    ('b1000000-0000-7000-8000-000000000001', 'f4000000-0000-7000-8000-000000000001', 'V4', 'Folio / matrícula', 4),
    ('b1000000-0000-7000-8000-000000000001', 'f4000000-0000-7000-8000-000000000001', 'V5', 'Cargas y gravámenes (estructura)', 5),
    ('b1000000-0000-7000-8000-000000000001', 'f4000000-0000-7000-8000-000000000001', 'V6', 'Listo para decisión humana', 6);

INSERT INTO app.integration (tenant_id, code, name, enabled)
SELECT 'b1000000-0000-7000-8000-000000000001', code, name, FALSE
FROM (VALUES
    ('QUIPUX', 'QUIPUX'),
    ('NOTARIA', 'Notaría'),
    ('MUNICIPIO', 'Municipio'),
    ('REGISTRO', 'Registro'),
    ('FIRMA', 'Firma'),
    ('PAGOS', 'Pagos')
) AS i(code, name);

INSERT INTO app.audit_event (tenant_id, actor_type, event, object_type, object_id, result, correlation_id)
VALUES (
    'b1000000-0000-7000-8000-000000000001',
    'SYSTEM',
    'TENANT_SEEDED',
    'tenant',
    'b1000000-0000-7000-8000-000000000001',
    'OK',
    'f5000000-0000-7000-8000-000000000001'
);
