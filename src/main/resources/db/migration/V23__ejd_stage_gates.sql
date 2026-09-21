-- Gates requeridos por etapa EJD (control antes de avanzar).

CREATE TABLE app.ejd_stage_gate_req (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES control.tenant (id),
    stage_code      VARCHAR(16) NOT NULL,
    gate_code       VARCHAR(32) NOT NULL,
    sort_order      INTEGER NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, stage_code, gate_code)
);

INSERT INTO app.ejd_stage_gate_req (tenant_id, stage_code, gate_code, sort_order) VALUES
    ('b1000000-0000-7000-8000-000000000001', 'e1', 'G-001', 1),
    ('b1000000-0000-7000-8000-000000000001', 'e2', 'G-001', 1),
    ('b1000000-0000-7000-8000-000000000001', 'e2', 'G-002', 2),
    ('b1000000-0000-7000-8000-000000000001', 'e3', 'G-003', 1),
    ('b1000000-0000-7000-8000-000000000001', 'e5', 'G-004', 1),
    ('b1000000-0000-7000-8000-000000000001', 'e8', 'G-005', 1);
