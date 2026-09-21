-- LEXIA-18 R1: versionado y publicación explícita de parametrización de proceso.

ALTER TABLE app.process_definition
    ADD COLUMN IF NOT EXISTS config_version INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS has_unpublished_changes BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS last_published_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS last_published_by UUID;

ALTER TABLE app.legal_case
    ADD COLUMN IF NOT EXISTS process_config_version INTEGER;

CREATE TABLE app.process_config_publication (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               UUID NOT NULL REFERENCES control.tenant (id),
    process_definition_id   UUID NOT NULL,
    config_version          INTEGER NOT NULL,
    comment                 VARCHAR(2000) NOT NULL,
    published_by            UUID NOT NULL,
    published_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (process_definition_id, tenant_id)
        REFERENCES app.process_definition (id, tenant_id)
);

CREATE INDEX ix_process_config_publication_process
    ON app.process_config_publication (tenant_id, process_definition_id, published_at DESC);

INSERT INTO app.permission (id, code, name, module_code) VALUES
    ('f1000000-0000-7000-8000-000000000015', 'admin:proceso:publicar', 'Publicar parametrización de proceso', 'admin')
ON CONFLICT (code) DO NOTHING;

INSERT INTO app.role_permission (role_id, permission_id, tenant_id)
SELECT 'f2000000-0000-7000-8000-000000000001', id, 'b1000000-0000-7000-8000-000000000001'
FROM app.permission
WHERE code = 'admin:proceso:publicar'
ON CONFLICT DO NOTHING;

UPDATE app.process_definition
SET has_unpublished_changes = FALSE,
    config_version = COALESCE(config_version, 1),
    last_published_at = COALESCE(last_published_at, now())
WHERE deleted_at IS NULL;

INSERT INTO app.process_config_publication (tenant_id, process_definition_id, config_version, comment, published_by, published_at)
SELECT p.tenant_id,
       p.id,
       p.config_version,
       'Baseline inicial (migración V26)',
       'c1000000-0000-7000-8000-000000000001',
       COALESCE(p.last_published_at, now())
FROM app.process_definition p
WHERE p.deleted_at IS NULL
  AND NOT EXISTS (
      SELECT 1 FROM app.process_config_publication pub
      WHERE pub.process_definition_id = p.id AND pub.tenant_id = p.tenant_id
  );
