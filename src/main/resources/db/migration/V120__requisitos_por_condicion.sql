-- Escrituración, fase 2: requisitos por producto, cantón y condición del caso.
-- - document_requirement gana condicion, por_persona, rol_persona y esencial.
-- - Tipos documentales nuevos del detalle documental de la abogada (catálogo DOC_TYPE).
-- - Seed idempotente por tenant: esenciales en todo producto (incluida la escritura antecedente),
--   requisitos de los 9 cantones del detalle y requisitos condicionales.
-- - app.expediente_condicion: condiciones del caso (fase 3 la llena; aquí solo la estructura).
-- - app.document.descripcion_tipo: descripción de los documentos OTRO (antes en observacion).
-- Corre como dueño de las tablas (sin FORCE RLS): ve y escribe filas de todos los tenants.

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM app.document_requirement) THEN
        RAISE WARNING 'V120: app.document_requirement no tiene filas visibles para este rol; el seed no insertará requisitos';
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- 1. Columnas nuevas de la matriz
-- ---------------------------------------------------------------------------
ALTER TABLE app.document_requirement
    ADD COLUMN IF NOT EXISTS condicion   VARCHAR(40),
    ADD COLUMN IF NOT EXISTS por_persona BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS rol_persona VARCHAR(16),
    ADD COLUMN IF NOT EXISTS esencial    BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE app.document_requirement DROP CONSTRAINT IF EXISTS ck_document_requirement_condicion;
ALTER TABLE app.document_requirement
    ADD CONSTRAINT ck_document_requirement_condicion
        CHECK (condicion IS NULL OR condicion IN ('COMPANIA_VENDEDORA', 'COMPANIA_COMPRADORA', 'HEREDEROS', 'PROPIEDAD_HORIZONTAL', 'PREDIO_RUSTICO', 'ENTREGA_OBRA', 'CAPITULACIONES', 'DISOLUCION_SOCIEDAD_CONYUGAL', 'COMPARECE_CON_PODER', 'VENDEDOR_MENOR_EDAD', 'EXTRANJERO', 'COLINDA_PLAYA_RIO', 'CREDITO_PREFERENCIAL', 'FINANCIAMIENTO_GASTOS'));
ALTER TABLE app.document_requirement DROP CONSTRAINT IF EXISTS ck_document_requirement_rol;
ALTER TABLE app.document_requirement
    ADD CONSTRAINT ck_document_requirement_rol
        CHECK (rol_persona IS NULL OR rol_persona IN ('COMPRADOR', 'VENDEDOR', 'AMBOS', 'REPRESENTANTE'));

-- La clave deja de ser (tenant, producto, tipo, cantón): un mismo tipo puede pedirse en general y
-- además por condición (p. ej. la cédula del representante de una compañía).
DO $$
DECLARE
    r record;
BEGIN
    FOR r IN
        SELECT c.conname
        FROM pg_constraint c
        WHERE c.conrelid = 'app.document_requirement'::regclass
          AND c.contype = 'u'
          AND c.conname <> 'uq_document_requirement_regla'
          AND (SELECT array_agg(a.attname::text ORDER BY a.attname)
               FROM pg_attribute a
               WHERE a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey))
              = ARRAY['canton', 'document_type_code', 'product_code', 'tenant_id']
    LOOP
        EXECUTE format('ALTER TABLE app.document_requirement DROP CONSTRAINT %I', r.conname);
    END LOOP;
END $$;

ALTER TABLE app.document_requirement DROP CONSTRAINT IF EXISTS uq_document_requirement_regla;
ALTER TABLE app.document_requirement
    ADD CONSTRAINT uq_document_requirement_regla
        UNIQUE NULLS NOT DISTINCT (tenant_id, product_code, document_type_code, canton, condicion, rol_persona);

-- ---------------------------------------------------------------------------
-- 2. Descripción de documentos OTRO
-- ---------------------------------------------------------------------------
ALTER TABLE app.document ADD COLUMN IF NOT EXISTS descripcion_tipo VARCHAR(120);

UPDATE app.document
SET descripcion_tipo = left(observacion, 120),
    observacion = NULL
WHERE doc_type = 'OTRO' AND observacion IS NOT NULL AND descripcion_tipo IS NULL;

-- ---------------------------------------------------------------------------
-- 3. Tipos documentales nuevos (catálogo DOC_TYPE de cada tenant)
-- ---------------------------------------------------------------------------
INSERT INTO app.catalog_item (tenant_id, catalog_id, code, label, sort_order)
SELECT c.tenant_id, c.id, t.code, t.label, t.sort_order
FROM app.catalog c
CROSS JOIN (VALUES
    ('CEDULA', 'Cédula de identidad', 40),
    ('PAPELETA', 'Certificado de votación', 41),
    ('HISTORIA_DOMINIO', 'Certificado de historia de dominio y gravámenes', 42),
    ('ESCRITURA_ANTECEDENTE', 'Escritura anterior (título de dominio)', 43),
    ('ACTA_MATRIMONIO_MARGINADA', 'Acta de matrimonio con marginación', 44),
    ('CAPITULACIONES', 'Escritura de capitulaciones matrimoniales', 45),
    ('POSESION_EFECTIVA', 'Posesión efectiva', 46),
    ('PARTICION', 'Partición y extinción de la comunidad sucesoria', 47),
    ('AUTORIZACION_JUDICIAL', 'Autorización judicial para la venta', 48),
    ('PODER', 'Poder', 49),
    ('RAZON_VIGENCIA_PODER', 'Razón de vigencia del poder', 50),
    ('CERT_INSPECCION_FINAL', 'Certificado de inspección final', 51),
    ('PERMISO_CONSTRUCCION', 'Permiso de construcción', 52),
    ('DOCS_CONSTRUCTOR', 'Documentos del constructor (cédula, votación, RUC, nombramiento)', 53),
    ('CERT_DELIMITACION_PLAYA', 'Certificado de delimitación de zona de playa y bahía', 54),
    ('CERT_EXPENSAS', 'Certificado de estar al día en expensas', 55),
    ('DOCS_ADMINISTRADOR_PH', 'RUC, nombramiento y cédula del administrador del condominio', 56),
    ('REGLAMENTO_PH', 'Reglamento interno de propiedad horizontal', 57),
    ('ESCRITURA_PH', 'Escritura de propiedad horizontal', 58),
    ('CERT_LINDEROS', 'Certificado de linderos del municipio', 59),
    ('LEVANTAMIENTO_PLANIMETRICO', 'Levantamiento planimétrico', 60),
    ('CERT_NO_AFECTACION_MAG', 'Certificado de no afectación del MAG', 61),
    ('CERT_NO_ADEUDAR_BANECUADOR', 'Certificado de no adeudar a BanEcuador', 62),
    ('CERT_NO_ADEUDAR_AGUAPEN', 'Certificado de no adeudar a AGUAPEN', 63),
    ('CERT_NO_ADEUDAR_EMSABA', 'Certificado de no adeudar a EMSABA-EP', 64),
    ('INFORME_MEDICION', 'Informe de medición', 65),
    ('DELINEACION_SOLAR', 'Delineación del solar', 66),
    ('AVALUO_TRANSFERENCIA', 'Certificado de avalúo para la transferencia', 67),
    ('RUC_O_SIN_ACTIVIDAD', 'RUC o certificado de no tener actividades económicas', 68)
) AS t(code, label, sort_order)
WHERE c.code = 'DOC_TYPE' AND c.deleted_at IS NULL
ON CONFLICT (catalog_id, code) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 4. Esenciales en todos los productos
-- ---------------------------------------------------------------------------
UPDATE app.document_requirement
SET esencial = TRUE, is_mandatory = TRUE
WHERE condicion IS NULL
  AND document_type_code IN ('CEDULA', 'PAPELETA', 'AVALUO', 'HISTORIA_DOMINIO', 'ESCRITURA_ANTECEDENTE');

UPDATE app.document_requirement
SET por_persona = TRUE, rol_persona = 'AMBOS'
WHERE condicion IS NULL AND rol_persona IS NULL
  AND document_type_code IN ('CEDULA', 'PAPELETA');

INSERT INTO app.document_requirement
    (tenant_id, product_code, document_type_code, is_mandatory, max_validity_days, canton, description,
     sort_order, condicion, por_persona, rol_persona, esencial)
SELECT p.tenant_id, p.product_code, e.doc, TRUE, 60, 'ALL', e.descr, e.sort_order, NULL, e.por_persona, e.rol, TRUE
FROM (SELECT DISTINCT tenant_id, product_code FROM app.document_requirement) AS p
CROSS JOIN (VALUES
    ('CEDULA', 'Cédula de compradores y vendedores', TRUE, 'AMBOS', 1),
    ('PAPELETA', 'Certificado de votación de compradores y vendedores', TRUE, 'AMBOS', 2),
    ('AVALUO', 'Certificado de avalúo actualizado', FALSE, NULL, 3),
    ('HISTORIA_DOMINIO', 'Certificado de historia de dominio y gravámenes', FALSE, NULL, 4),
    ('ESCRITURA_ANTECEDENTE', 'Escritura anterior (título de dominio)', FALSE, NULL, 5)
) AS e(doc, descr, por_persona, rol, sort_order)
WHERE NOT EXISTS (
    SELECT 1 FROM app.document_requirement x
    WHERE x.tenant_id = p.tenant_id AND x.product_code = p.product_code
      AND x.document_type_code = e.doc AND x.canton = 'ALL' AND x.condicion IS NULL
)
ON CONFLICT ON CONSTRAINT uq_document_requirement_regla DO NOTHING;

-- ---------------------------------------------------------------------------
-- 5. Requisitos condicionales (todos los productos)
-- ---------------------------------------------------------------------------
INSERT INTO app.document_requirement
    (tenant_id, product_code, document_type_code, is_mandatory, max_validity_days, canton, description,
     sort_order, condicion, por_persona, rol_persona, esencial)
SELECT p.tenant_id, p.product_code, c.doc, c.obligatorio, 60, 'ALL', c.descr, c.sort_order,
       c.condicion, c.por_persona, c.rol, c.esencial
FROM (SELECT DISTINCT tenant_id, product_code FROM app.document_requirement) AS p
CROSS JOIN (VALUES
    ('CAPITULACIONES', 'CAPITULACIONES', TRUE, FALSE, FALSE, NULL, 'Escritura de capitulaciones matrimoniales con sus ampliaciones', 30),
    ('CAPITULACIONES', 'ACTA_MATRIMONIO_MARGINADA', TRUE, FALSE, FALSE, NULL, 'Acta de matrimonio con la marginación de capitulaciones', 31),
    ('DISOLUCION_SOCIEDAD_CONYUGAL', 'ACTA_MATRIMONIO_MARGINADA', TRUE, FALSE, FALSE, NULL, 'Acta de matrimonio con la marginación de la disolución', 32),
    ('COMPANIA_VENDEDORA', 'NOMBRAMIENTO_RL', TRUE, FALSE, FALSE, NULL, 'Nombramiento del representante legal de la compañía', 33),
    ('COMPANIA_VENDEDORA', 'RUC', TRUE, FALSE, FALSE, NULL, 'RUC de la compañía', 34),
    ('COMPANIA_VENDEDORA', 'CEDULA', TRUE, FALSE, TRUE, 'REPRESENTANTE', 'Cédula del representante legal', 35),
    ('COMPANIA_VENDEDORA', 'PAPELETA', TRUE, FALSE, TRUE, 'REPRESENTANTE', 'Certificado de votación del representante legal', 36),
    ('COMPANIA_VENDEDORA', 'ACTA_JUNTA', FALSE, FALSE, FALSE, NULL, 'Acta de junta (no se pide si el objeto social del RUC es inmobiliario)', 37),
    ('COMPANIA_COMPRADORA', 'NOMBRAMIENTO_RL', TRUE, FALSE, FALSE, NULL, 'Nombramiento del representante legal de la compañía', 33),
    ('COMPANIA_COMPRADORA', 'RUC', TRUE, FALSE, FALSE, NULL, 'RUC de la compañía', 34),
    ('COMPANIA_COMPRADORA', 'CEDULA', TRUE, FALSE, TRUE, 'REPRESENTANTE', 'Cédula del representante legal', 35),
    ('COMPANIA_COMPRADORA', 'PAPELETA', TRUE, FALSE, TRUE, 'REPRESENTANTE', 'Certificado de votación del representante legal', 36),
    ('COMPANIA_COMPRADORA', 'ACTA_JUNTA', FALSE, FALSE, FALSE, NULL, 'Acta de junta (no se pide si el objeto social del RUC es inmobiliario)', 37),
    ('VENDEDOR_MENOR_EDAD', 'AUTORIZACION_JUDICIAL', TRUE, FALSE, FALSE, NULL, 'Autorización judicial para la venta de bienes de menores', 38),
    ('COMPARECE_CON_PODER', 'PODER', TRUE, TRUE, FALSE, NULL, 'Poder (vigente; con más de un año requiere razón de vigencia)', 39),
    ('ENTREGA_OBRA', 'DOCS_CONSTRUCTOR', TRUE, FALSE, FALSE, NULL, 'Cédula, votación y RUC del constructor; nombramiento si es compañía', 40),
    ('ENTREGA_OBRA', 'CERT_INSPECCION_FINAL', TRUE, FALSE, FALSE, NULL, 'Certificado de inspección final', 41),
    ('ENTREGA_OBRA', 'PERMISO_CONSTRUCCION', TRUE, FALSE, FALSE, NULL, 'Permiso de construcción', 42),
    ('CREDITO_PREFERENCIAL', 'CERT_NO_POSEER_BIENES', TRUE, FALSE, FALSE, NULL, 'Certificado de no poseer bienes', 43),
    ('HEREDEROS', 'POSESION_EFECTIVA', TRUE, FALSE, FALSE, NULL, 'Posesión efectiva', 44),
    ('HEREDEROS', 'PARTICION', FALSE, FALSE, FALSE, NULL, 'Partición y extinción de la comunidad sucesoria (cuando son varios herederos)', 45),
    ('COLINDA_PLAYA_RIO', 'CERT_DELIMITACION_PLAYA', TRUE, FALSE, FALSE, NULL, 'Certificado de delimitación de zona de playa y bahía', 46),
    ('PROPIEDAD_HORIZONTAL', 'CERT_EXPENSAS', TRUE, FALSE, FALSE, NULL, 'Certificado de expensas al día (del mes de la firma)', 47),
    ('PROPIEDAD_HORIZONTAL', 'DOCS_ADMINISTRADOR_PH', TRUE, FALSE, FALSE, NULL, 'RUC, nombramiento y cédula del administrador', 48),
    ('PROPIEDAD_HORIZONTAL', 'REGLAMENTO_PH', TRUE, FALSE, FALSE, NULL, 'Reglamento interno de propiedad horizontal', 49),
    ('PROPIEDAD_HORIZONTAL', 'ESCRITURA_PH', FALSE, FALSE, FALSE, NULL, 'Escritura de propiedad horizontal con la declaratoria municipal', 50),
    ('PREDIO_RUSTICO', 'CERT_LINDEROS', TRUE, FALSE, FALSE, NULL, 'Certificado de linderos del municipio', 51),
    ('PREDIO_RUSTICO', 'LEVANTAMIENTO_PLANIMETRICO', TRUE, FALSE, FALSE, NULL, 'Levantamiento planimétrico del inmueble', 52),
    ('PREDIO_RUSTICO', 'CERT_NO_AFECTACION_MAG', TRUE, FALSE, FALSE, NULL, 'Certificado de no afectación del MAG', 53),
    ('PREDIO_RUSTICO', 'CERT_NO_ADEUDAR_BANECUADOR', TRUE, FALSE, FALSE, NULL, 'Certificado de no adeudar a BanEcuador', 54)
) AS c(condicion, doc, obligatorio, esencial, por_persona, rol, descr, sort_order)
ON CONFLICT ON CONSTRAINT uq_document_requirement_regla DO NOTHING;

-- ---------------------------------------------------------------------------
-- 6. Requisitos por cantón (los 9 del detalle documental; no aplica a sustitución de hipoteca,
--    igual que los extras de V43). Guayaquil y el resto usan solo los generales.
-- ---------------------------------------------------------------------------
INSERT INTO app.document_requirement
    (tenant_id, product_code, document_type_code, is_mandatory, max_validity_days, canton, description,
     sort_order, condicion, por_persona, rol_persona, esencial)
SELECT p.tenant_id, p.product_code, k.doc, k.obligatorio, 60, k.canton, k.descr, k.sort_order,
       k.condicion, k.por_persona, k.rol, FALSE
FROM (SELECT DISTINCT tenant_id, product_code FROM app.document_requirement
      WHERE product_code <> 'SUSTITUCION_HIPOTECA') AS p
CROSS JOIN (VALUES
    ('DAULE', NULL, 'CERT_BOMBEROS', TRUE, FALSE, NULL, 'Comprobante del pago de bomberos', 90),
    ('DAULE', NULL, 'CERT_NO_ADEUDAR', TRUE, FALSE, NULL, 'Certificado de no adeudar al municipio', 91),
    ('DAULE', 'ENTREGA_OBRA', 'CERT_INSPECCION_FINAL', TRUE, FALSE, NULL, 'Inspección final (si el avalúo muestra edificación que no consta en la historia de dominio)', 92),
    ('DAULE', 'EXTRANJERO', 'RUC_O_SIN_ACTIVIDAD', TRUE, TRUE, 'VENDEDOR', 'RUC o certificado de no tener actividades económicas de vendedores extranjeros', 93),
    ('DAULE', 'PROPIEDAD_HORIZONTAL', 'ESCRITURA_PH', TRUE, FALSE, NULL, 'Escritura de propiedad horizontal con la declaratoria municipal', 94),
    ('SAMBORONDON', NULL, 'CERT_BOMBEROS', TRUE, FALSE, NULL, 'Comprobante del pago de bomberos', 90),
    ('SAMBORONDON', 'EXTRANJERO', 'RUC', TRUE, TRUE, 'VENDEDOR', 'RUC de vendedores extranjeros', 93),
    ('SAMBORONDON', 'ENTREGA_OBRA', 'CERT_INSPECCION_FINAL', TRUE, FALSE, NULL, 'Inspección final (construcción nueva / entrega de obra)', 92),
    ('DURAN', NULL, 'CERT_BOMBEROS', TRUE, FALSE, NULL, 'Comprobante del pago de bomberos', 90),
    ('DURAN', NULL, 'CERT_NO_ADEUDAR', TRUE, FALSE, NULL, 'Certificado de no adeudar al municipio', 91),
    ('DURAN', NULL, 'CERT_LINDEROS', TRUE, FALSE, NULL, 'Certificado de linderos', 95),
    ('DURAN', 'ENTREGA_OBRA', 'CERT_INSPECCION_FINAL', TRUE, FALSE, NULL, 'Inspección final (construcción nueva / entrega de obra)', 92),
    ('SALINAS', NULL, 'CERT_BOMBEROS', TRUE, FALSE, NULL, 'Comprobante del pago de bomberos', 90),
    ('SALINAS', NULL, 'CERT_NO_ADEUDAR', TRUE, FALSE, NULL, 'Certificado de no adeudar al municipio', 91),
    ('SALINAS', NULL, 'CERT_NO_ADEUDAR_AGUAPEN', TRUE, FALSE, NULL, 'Certificado de no adeudar a AGUAPEN', 96),
    ('SALINAS', 'EXTRANJERO', 'RUC', TRUE, TRUE, 'AMBOS', 'RUC de vendedores y compradores extranjeros', 93),
    ('LA LIBERTAD', NULL, 'CERT_BOMBEROS', TRUE, FALSE, NULL, 'Certificado de no adeudar al cuerpo de bomberos', 90),
    ('LA LIBERTAD', NULL, 'CERT_NO_ADEUDAR', TRUE, FALSE, NULL, 'Certificado de no adeudar al municipio', 91),
    ('LA LIBERTAD', NULL, 'CERT_NO_ADEUDAR_AGUAPEN', TRUE, FALSE, NULL, 'Certificado de no adeudar a AGUAPEN', 96),
    ('SANTA ELENA', NULL, 'CERT_BOMBEROS', TRUE, FALSE, NULL, 'Comprobante del pago de bomberos', 90),
    ('SANTA ELENA', NULL, 'CERT_NO_ADEUDAR', TRUE, FALSE, NULL, 'Certificado de no adeudar al municipio', 91),
    ('SANTA ELENA', NULL, 'CERT_NO_ADEUDAR_AGUAPEN', TRUE, FALSE, NULL, 'Certificado de no adeudar a AGUAPEN', 96),
    ('SANTA ELENA', NULL, 'AVALUO_TRANSFERENCIA', TRUE, FALSE, NULL, 'Certificado de avalúo para la transferencia', 97),
    ('PLAYAS', NULL, 'CERT_NO_ADEUDAR', TRUE, FALSE, NULL, 'Certificado de no adeudar al municipio', 91),
    ('PLAYAS', 'ENTREGA_OBRA', 'CERT_INSPECCION_FINAL', TRUE, FALSE, NULL, 'Inspección final (construcción nueva / entrega de obra)', 92),
    ('MILAGRO', NULL, 'CERT_BOMBEROS', TRUE, FALSE, NULL, 'Comprobante del pago de bomberos', 90),
    ('MILAGRO', NULL, 'CERT_NO_ADEUDAR', TRUE, FALSE, NULL, 'Certificado de no adeudar al municipio', 91),
    ('BABAHOYO', NULL, 'INFORME_MEDICION', TRUE, FALSE, NULL, 'Informe de medición', 98),
    ('BABAHOYO', NULL, 'DELINEACION_SOLAR', TRUE, FALSE, NULL, 'Delineación del solar', 99),
    ('BABAHOYO', NULL, 'CERT_NO_ADEUDAR_EMSABA', TRUE, FALSE, NULL, 'Certificado de no adeudar a EMSABA-EP', 100),
    ('BABAHOYO', NULL, 'CERT_NO_ADEUDAR', TRUE, FALSE, NULL, 'Certificado de no adeudar al municipio', 91)
) AS k(canton, condicion, doc, obligatorio, por_persona, rol, descr, sort_order)
ON CONFLICT ON CONSTRAINT uq_document_requirement_regla DO NOTHING;

-- Playas: el avalúo debe traer linderos y medidas (misma regla esencial, otra descripción).
INSERT INTO app.document_requirement
    (tenant_id, product_code, document_type_code, is_mandatory, max_validity_days, canton, description,
     sort_order, condicion, por_persona, rol_persona, esencial)
SELECT p.tenant_id, p.product_code, 'AVALUO', TRUE, 60, 'PLAYAS',
       'Certificado de avalúo con linderos y medidas del bien', 3, NULL, FALSE, NULL, TRUE
FROM (SELECT DISTINCT tenant_id, product_code FROM app.document_requirement
      WHERE product_code <> 'SUSTITUCION_HIPOTECA') AS p
ON CONFLICT ON CONSTRAINT uq_document_requirement_regla DO NOTHING;

CREATE INDEX IF NOT EXISTS ix_document_requirement_regla
    ON app.document_requirement (tenant_id, product_code, canton);

-- ---------------------------------------------------------------------------
-- 7. Condiciones del caso (borrador o expediente; la fase 3 las propone y confirma)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS app.expediente_condicion (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID NOT NULL REFERENCES control.tenant (id),
    -- id del borrador (app.expediente_borrador) o del expediente (app.legal_case)
    expediente_id  UUID NOT NULL,
    condicion      VARCHAR(40) NOT NULL CHECK (condicion IN ('COMPANIA_VENDEDORA', 'COMPANIA_COMPRADORA', 'HEREDEROS', 'PROPIEDAD_HORIZONTAL', 'PREDIO_RUSTICO', 'ENTREGA_OBRA', 'CAPITULACIONES', 'DISOLUCION_SOCIEDAD_CONYUGAL', 'COMPARECE_CON_PODER', 'VENDEDOR_MENOR_EDAD', 'EXTRANJERO', 'COLINDA_PLAYA_RIO', 'CREDITO_PREFERENCIAL', 'FINANCIAMIENTO_GASTOS')),
    origen         VARCHAR(8) NOT NULL CHECK (origen IN ('IA', 'MANUAL')),
    confirmada     BOOLEAN NOT NULL DEFAULT FALSE,
    descartada     BOOLEAN NOT NULL DEFAULT FALSE,
    evidencia      VARCHAR(500),
    documento_id   UUID,
    confianza      NUMERIC(4, 3),
    actualizado_por UUID,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, expediente_id, condicion)
);

CREATE INDEX IF NOT EXISTS ix_expediente_condicion_expediente
    ON app.expediente_condicion (tenant_id, expediente_id);

ALTER TABLE app.expediente_condicion ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON app.expediente_condicion;
CREATE POLICY tenant_isolation ON app.expediente_condicion
    USING (tenant_id = app.current_tenant_id())
    WITH CHECK (tenant_id = app.current_tenant_id());

GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE app.expediente_condicion TO lexia_app;
GRANT SELECT ON TABLE app.expediente_condicion TO lexia_readonly;

-- ---------------------------------------------------------------------------
-- 8. Alta de tenant: copia también las columnas nuevas (y la nueva clave)
-- ---------------------------------------------------------------------------
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
        tenant_id, product_code, document_type_code, is_mandatory, max_validity_days, canton, description,
        sort_order, condicion, por_persona, rol_persona, esencial)
    SELECT p_target, r.product_code, r.document_type_code, r.is_mandatory, r.max_validity_days,
           r.canton, r.description, r.sort_order, r.condicion, r.por_persona, r.rol_persona, r.esencial
    FROM app.document_requirement r
    WHERE r.tenant_id = v_source
    ON CONFLICT ON CONSTRAINT uq_document_requirement_regla DO NOTHING;

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

-- Los tenants existentes ya recibieron las filas en las secciones 3 a 6 (seed sobre todos los
-- tenants con matriz); la función solo cambia para las altas futuras.
