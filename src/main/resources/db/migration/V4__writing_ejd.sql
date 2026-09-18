-- Extensión EJD. No mezclar con coactivas. Sin reglas notariales reales.

CREATE TABLE app.writing_file (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES control.tenant (id),
    case_id         UUID NOT NULL,
    property_label  VARCHAR(240),
    folio           VARCHAR(80),
    act_type        VARCHAR(80),
    municipality    VARCHAR(160),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ,
    row_version     BIGINT NOT NULL DEFAULT 1,
    UNIQUE (id, tenant_id),
    UNIQUE (case_id),
    FOREIGN KEY (case_id, tenant_id) REFERENCES app.legal_case (id, tenant_id)
);

CREATE TABLE app.title_study (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES control.tenant (id),
    writing_file_id UUID NOT NULL,
    status          VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    summary         TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id, tenant_id),
    FOREIGN KEY (writing_file_id, tenant_id) REFERENCES app.writing_file (id, tenant_id)
);

CREATE TABLE app.title_observation (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES control.tenant (id),
    title_study_id  UUID NOT NULL,
    detail          TEXT NOT NULL,
    status          VARCHAR(32) NOT NULL DEFAULT 'OPEN',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id, tenant_id),
    FOREIGN KEY (title_study_id, tenant_id) REFERENCES app.title_study (id, tenant_id)
);

CREATE TABLE app.minuta_draft (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES control.tenant (id),
    writing_file_id UUID NOT NULL,
    document_version_id UUID,
    status          VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id, tenant_id),
    FOREIGN KEY (writing_file_id, tenant_id) REFERENCES app.writing_file (id, tenant_id),
    FOREIGN KEY (document_version_id, tenant_id) REFERENCES app.document_version (id, tenant_id)
);

CREATE TABLE app.notary_act (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES control.tenant (id),
    writing_file_id UUID NOT NULL,
    notary_name     VARCHAR(200),
    protocol_ref    VARCHAR(80),
    status          VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    scheduled_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id, tenant_id),
    FOREIGN KEY (writing_file_id, tenant_id) REFERENCES app.writing_file (id, tenant_id)
);

CREATE TABLE app.municipal_tax (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES control.tenant (id),
    writing_file_id UUID NOT NULL,
    tax_type        VARCHAR(80),
    amount_ref      VARCHAR(40),
    status          VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id, tenant_id),
    FOREIGN KEY (writing_file_id, tenant_id) REFERENCES app.writing_file (id, tenant_id)
);

CREATE TABLE app.registry_inscription (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES control.tenant (id),
    writing_file_id UUID NOT NULL,
    office_name     VARCHAR(200),
    inscription_ref VARCHAR(80),
    status          VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id, tenant_id),
    FOREIGN KEY (writing_file_id, tenant_id) REFERENCES app.writing_file (id, tenant_id)
);
