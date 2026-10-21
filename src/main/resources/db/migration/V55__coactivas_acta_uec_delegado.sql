ALTER TABLE app.coactiva_acta
    ADD COLUMN uec_nombre VARCHAR(120),
    ADD COLUMN delegado_id UUID;

ALTER TABLE app.coactiva_acta
    ADD CONSTRAINT fk_coactiva_acta_delegado
        FOREIGN KEY (delegado_id, tenant_id) REFERENCES app.coactiva_delegado (id, tenant_id);

ALTER TABLE app.coactiva_acta_item
    ADD COLUMN delegado_id UUID;

ALTER TABLE app.coactiva_acta_item
    ADD CONSTRAINT fk_coactiva_acta_item_delegado
        FOREIGN KEY (delegado_id, tenant_id) REFERENCES app.coactiva_delegado (id, tenant_id);
