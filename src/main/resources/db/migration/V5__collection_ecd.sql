-- Extensión ECD. No mezclar con escrituración. Sin cuantías jurídicas reales.

CREATE TABLE app.collection_file (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES control.tenant (id),
    case_id         UUID NOT NULL,
    creditor_label  VARCHAR(240),
    opening_ref     VARCHAR(80),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ,
    row_version     BIGINT NOT NULL DEFAULT 1,
    UNIQUE (id, tenant_id),
    UNIQUE (case_id),
    FOREIGN KEY (case_id, tenant_id) REFERENCES app.legal_case (id, tenant_id)
);

CREATE TABLE app.obligation (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES control.tenant (id),
    collection_file_id  UUID NOT NULL,
    amount_ref          VARCHAR(40),
    period_label        VARCHAR(80),
    executive_title_ref VARCHAR(120),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id, tenant_id),
    FOREIGN KEY (collection_file_id, tenant_id) REFERENCES app.collection_file (id, tenant_id)
);

CREATE TABLE app.mandamiento (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES control.tenant (id),
    collection_file_id  UUID NOT NULL,
    issued_at           TIMESTAMPTZ,
    status              VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    document_version_id UUID,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id, tenant_id),
    FOREIGN KEY (collection_file_id, tenant_id) REFERENCES app.collection_file (id, tenant_id),
    FOREIGN KEY (document_version_id, tenant_id) REFERENCES app.document_version (id, tenant_id)
);

CREATE TABLE app.enforcement_measure (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES control.tenant (id),
    collection_file_id  UUID NOT NULL,
    measure_type        VARCHAR(80) NOT NULL,
    status              VARCHAR(32) NOT NULL DEFAULT 'REQUESTED',
    detail              TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id, tenant_id),
    FOREIGN KEY (collection_file_id, tenant_id) REFERENCES app.collection_file (id, tenant_id)
);

CREATE TABLE app.collection_tracking (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES control.tenant (id),
    collection_file_id  UUID NOT NULL,
    note                TEXT NOT NULL,
    tracked_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id, tenant_id),
    FOREIGN KEY (collection_file_id, tenant_id) REFERENCES app.collection_file (id, tenant_id)
);
