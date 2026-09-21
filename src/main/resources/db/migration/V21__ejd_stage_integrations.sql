-- Matriz etapa EJD (E1–E8) × conectores institucionales (TO-BE Escrituración).

CREATE TABLE app.ejd_stage_integration (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES control.tenant (id),
    stage_code          VARCHAR(16) NOT NULL,
    integration_code    VARCHAR(64) NOT NULL,
    sort_order          INTEGER NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, stage_code, integration_code)
);

INSERT INTO app.ejd_stage_integration (tenant_id, stage_code, integration_code, sort_order) VALUES
    ('b1000000-0000-7000-8000-000000000001', 'e4', 'FIRMA', 1),
    ('b1000000-0000-7000-8000-000000000001', 'e5', 'NOTARIA', 1),
    ('b1000000-0000-7000-8000-000000000001', 'e6', 'MUNICIPIO', 1),
    ('b1000000-0000-7000-8000-000000000001', 'e6', 'PAGOS', 2),
    ('b1000000-0000-7000-8000-000000000001', 'e8', 'REGISTRO', 1),
    ('b1000000-0000-7000-8000-000000000001', 'e8', 'QUIPUX', 2);
