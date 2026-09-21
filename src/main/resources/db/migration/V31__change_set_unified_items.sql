-- LEXIA-18: ítems de ChangeSet (proceso, reglas, calendario, etc.) en un mismo paquete de promoción.

CREATE TABLE app.process_config_change_set_item (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    change_set_id   UUID NOT NULL REFERENCES app.process_config_change_set (id) ON DELETE CASCADE,
    tenant_id       UUID NOT NULL REFERENCES control.tenant (id),
    domain          VARCHAR(32) NOT NULL,
    summary         VARCHAR(500) NOT NULL,
    entity_ref      VARCHAR(128),
    last_updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (change_set_id, domain)
);

CREATE INDEX ix_process_config_change_set_item_cs
    ON app.process_config_change_set_item (change_set_id, domain);
