-- Transiciones permitidas entre etapas EJD (cadena TO-BE E1→E8).

CREATE TABLE app.process_stage_transition (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               UUID NOT NULL REFERENCES control.tenant (id),
    process_definition_id   UUID NOT NULL,
    from_stage_code         VARCHAR(16) NOT NULL,
    to_stage_code           VARCHAR(16) NOT NULL,
    UNIQUE (tenant_id, process_definition_id, from_stage_code, to_stage_code),
    FOREIGN KEY (process_definition_id, tenant_id)
        REFERENCES app.process_definition (id, tenant_id)
);

INSERT INTO app.process_stage_transition (tenant_id, process_definition_id, from_stage_code, to_stage_code) VALUES
    ('b1000000-0000-7000-8000-000000000001', 'f4000000-0000-7000-8000-000000000001', 'e1', 'e2'),
    ('b1000000-0000-7000-8000-000000000001', 'f4000000-0000-7000-8000-000000000001', 'e2', 'e3'),
    ('b1000000-0000-7000-8000-000000000001', 'f4000000-0000-7000-8000-000000000001', 'e3', 'e4'),
    ('b1000000-0000-7000-8000-000000000001', 'f4000000-0000-7000-8000-000000000001', 'e4', 'e5'),
    ('b1000000-0000-7000-8000-000000000001', 'f4000000-0000-7000-8000-000000000001', 'e5', 'e6'),
    ('b1000000-0000-7000-8000-000000000001', 'f4000000-0000-7000-8000-000000000001', 'e6', 'e7'),
    ('b1000000-0000-7000-8000-000000000001', 'f4000000-0000-7000-8000-000000000001', 'e7', 'e8');
