-- Núcleo de expediente, proceso, documento y trazabilidad.
-- No implementa reglas jurídicas ni umbrales.

CREATE TABLE app.process_definition (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES control.tenant (id),
    code            VARCHAR(32) NOT NULL,
    name            VARCHAR(160) NOT NULL,
    case_type       VARCHAR(8) NOT NULL CHECK (case_type IN ('EJD', 'ECD')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ,
    row_version     BIGINT NOT NULL DEFAULT 1,
    UNIQUE (id, tenant_id),
    UNIQUE (tenant_id, code)
);

CREATE TABLE app.process_stage_def (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES control.tenant (id),
    process_definition_id UUID NOT NULL,
    code            VARCHAR(16) NOT NULL,
    label           VARCHAR(160) NOT NULL,
    short_label     VARCHAR(64) NOT NULL,
    sort_order      INTEGER NOT NULL,
    UNIQUE (id, tenant_id),
    UNIQUE (process_definition_id, code),
    FOREIGN KEY (process_definition_id, tenant_id)
        REFERENCES app.process_definition (id, tenant_id)
);

CREATE TABLE app.gate_def (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES control.tenant (id),
    process_definition_id UUID NOT NULL,
    code            VARCHAR(32) NOT NULL,
    question        TEXT NOT NULL,
    sort_order      INTEGER NOT NULL DEFAULT 0,
    UNIQUE (id, tenant_id),
    UNIQUE (process_definition_id, code),
    FOREIGN KEY (process_definition_id, tenant_id)
        REFERENCES app.process_definition (id, tenant_id)
);

CREATE TABLE app.validation_def (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES control.tenant (id),
    process_definition_id UUID NOT NULL,
    code            VARCHAR(32) NOT NULL,
    label           VARCHAR(200) NOT NULL,
    sort_order      INTEGER NOT NULL DEFAULT 0,
    UNIQUE (id, tenant_id),
    UNIQUE (process_definition_id, code),
    FOREIGN KEY (process_definition_id, tenant_id)
        REFERENCES app.process_definition (id, tenant_id)
);

CREATE TABLE app.rule_def (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES control.tenant (id),
    code            VARCHAR(64) NOT NULL,
    version         INTEGER NOT NULL DEFAULT 1,
    name            VARCHAR(200) NOT NULL,
    body            TEXT,
    status          VARCHAR(24) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id, tenant_id),
    UNIQUE (tenant_id, code, version)
);

CREATE TABLE app.legal_case (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               UUID NOT NULL REFERENCES control.tenant (id),
    legal_entity_id         UUID,
    code                    VARCHAR(40) NOT NULL,
    case_type               VARCHAR(8) NOT NULL CHECK (case_type IN ('EJD', 'ECD')),
    vertical                VARCHAR(64) NOT NULL,
    subject                 VARCHAR(400) NOT NULL,
    status                  VARCHAR(40) NOT NULL DEFAULT 'DRAFT',
    priority                VARCHAR(16) NOT NULL DEFAULT 'MEDIA'
        CHECK (priority IN ('ALTA', 'MEDIA', 'BAJA')),
    responsible_membership_id UUID,
    process_definition_id   UUID,
    current_stage_id        UUID,
    sla_due_at              TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by              UUID,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by              UUID,
    deleted_at              TIMESTAMPTZ,
    row_version             BIGINT NOT NULL DEFAULT 1,
    UNIQUE (id, tenant_id),
    UNIQUE (tenant_id, code),
    FOREIGN KEY (legal_entity_id, tenant_id)
        REFERENCES app.legal_entity (id, tenant_id),
    FOREIGN KEY (process_definition_id, tenant_id)
        REFERENCES app.process_definition (id, tenant_id),
    FOREIGN KEY (responsible_membership_id, tenant_id)
        REFERENCES app.membership (id, tenant_id)
);

CREATE INDEX ix_legal_case_tenant_status ON app.legal_case (tenant_id, status, updated_at DESC);

CREATE TABLE app.case_party (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES control.tenant (id),
    case_id         UUID NOT NULL,
    kind            VARCHAR(16) NOT NULL CHECK (kind IN ('CLIENT', 'PARTY', 'OTHER')),
    display_name    VARCHAR(240) NOT NULL,
    role_label      VARCHAR(160),
    identification  VARCHAR(64),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ,
    UNIQUE (id, tenant_id),
    FOREIGN KEY (case_id, tenant_id) REFERENCES app.legal_case (id, tenant_id)
);

CREATE TABLE app.case_assignment (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES control.tenant (id),
    case_id         UUID NOT NULL,
    membership_id   UUID NOT NULL,
    assigned_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    unassigned_at   TIMESTAMPTZ,
    UNIQUE (id, tenant_id),
    FOREIGN KEY (case_id, tenant_id) REFERENCES app.legal_case (id, tenant_id),
    FOREIGN KEY (membership_id, tenant_id) REFERENCES app.membership (id, tenant_id)
);

CREATE TABLE app.case_note (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES control.tenant (id),
    case_id         UUID NOT NULL,
    author_user_id  UUID REFERENCES app.app_user (id),
    content         TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ,
    UNIQUE (id, tenant_id),
    FOREIGN KEY (case_id, tenant_id) REFERENCES app.legal_case (id, tenant_id)
);

CREATE TABLE app.case_stage (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES control.tenant (id),
    case_id         UUID NOT NULL,
    stage_def_id    UUID NOT NULL,
    status          VARCHAR(24) NOT NULL
        CHECK (status IN ('completed', 'current', 'pending', 'exception')),
    started_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    UNIQUE (id, tenant_id),
    UNIQUE (case_id, stage_def_id),
    FOREIGN KEY (case_id, tenant_id) REFERENCES app.legal_case (id, tenant_id),
    FOREIGN KEY (stage_def_id, tenant_id) REFERENCES app.process_stage_def (id, tenant_id)
);

ALTER TABLE app.legal_case
    ADD CONSTRAINT fk_legal_case_current_stage
    FOREIGN KEY (current_stage_id, tenant_id)
    REFERENCES app.case_stage (id, tenant_id);

CREATE TABLE app.case_gate (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES control.tenant (id),
    case_id         UUID NOT NULL,
    gate_def_id     UUID NOT NULL,
    result          VARCHAR(40) NOT NULL DEFAULT 'NOT_EVALUATED',
    UNIQUE (id, tenant_id),
    FOREIGN KEY (case_id, tenant_id) REFERENCES app.legal_case (id, tenant_id),
    FOREIGN KEY (gate_def_id, tenant_id) REFERENCES app.gate_def (id, tenant_id)
);

CREATE TABLE app.document (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES control.tenant (id),
    case_id         UUID,
    name            VARCHAR(320) NOT NULL,
    doc_type        VARCHAR(80),
    classification  VARCHAR(40),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ,
    row_version     BIGINT NOT NULL DEFAULT 1,
    UNIQUE (id, tenant_id),
    FOREIGN KEY (case_id, tenant_id) REFERENCES app.legal_case (id, tenant_id)
);

CREATE TABLE app.document_version (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES control.tenant (id),
    document_id     UUID NOT NULL,
    version_no      INTEGER NOT NULL,
    origin          VARCHAR(32) NOT NULL CHECK (origin IN ('UPLOAD', 'INTEGRATION')),
    mime_type       VARCHAR(128),
    content_hash    VARCHAR(128) NOT NULL,
    storage_key     VARCHAR(512) NOT NULL,
    status          VARCHAR(32) NOT NULL DEFAULT 'STORED',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id, tenant_id),
    UNIQUE (document_id, version_no),
    FOREIGN KEY (document_id, tenant_id) REFERENCES app.document (id, tenant_id)
);

CREATE TABLE app.case_validation (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES control.tenant (id),
    case_id         UUID NOT NULL,
    validation_def_id UUID,
    label           VARCHAR(200) NOT NULL,
    kind            VARCHAR(40) NOT NULL,
    result          VARCHAR(32) NOT NULL
        CHECK (result IN ('PASS', 'FAIL', 'REVIEW_REQUIRED', 'NOT_APPLICABLE', 'NOT_RUN')),
    evidence        TEXT,
    rule_version    VARCHAR(32),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id, tenant_id),
    FOREIGN KEY (case_id, tenant_id) REFERENCES app.legal_case (id, tenant_id),
    FOREIGN KEY (validation_def_id, tenant_id) REFERENCES app.validation_def (id, tenant_id)
);

CREATE TABLE app.extracted_data (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES control.tenant (id),
    case_id         UUID NOT NULL,
    document_id     UUID,
    field_label     VARCHAR(200) NOT NULL,
    field_value     TEXT,
    field_group     VARCHAR(80),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id, tenant_id),
    FOREIGN KEY (case_id, tenant_id) REFERENCES app.legal_case (id, tenant_id),
    FOREIGN KEY (document_id, tenant_id) REFERENCES app.document (id, tenant_id)
);

CREATE TABLE app.data_provenance (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES control.tenant (id),
    extracted_data_id   UUID NOT NULL,
    source              VARCHAR(80),
    document_name       VARCHAR(320),
    page_label          VARCHAR(32),
    method              VARCHAR(64),
    confidence          VARCHAR(16),
    validation_label    VARCHAR(80),
    UNIQUE (id, tenant_id),
    UNIQUE (extracted_data_id),
    FOREIGN KEY (extracted_data_id, tenant_id) REFERENCES app.extracted_data (id, tenant_id)
);

CREATE TABLE app.legal_review (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES control.tenant (id),
    case_id         UUID NOT NULL,
    status          VARCHAR(40) NOT NULL DEFAULT 'PENDING',
    owner_membership_id UUID,
    findings        TEXT,
    decisions       TEXT,
    evidence        TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id, tenant_id),
    FOREIGN KEY (case_id, tenant_id) REFERENCES app.legal_case (id, tenant_id),
    FOREIGN KEY (owner_membership_id, tenant_id) REFERENCES app.membership (id, tenant_id)
);

CREATE TABLE app.human_decision (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES control.tenant (id),
    case_id         UUID NOT NULL,
    actor_user_id   UUID REFERENCES app.app_user (id),
    decided_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    decision        VARCHAR(240) NOT NULL,
    reason          TEXT,
    evidence        TEXT,
    UNIQUE (id, tenant_id),
    FOREIGN KEY (case_id, tenant_id) REFERENCES app.legal_case (id, tenant_id)
);

CREATE TABLE app.case_task (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES control.tenant (id),
    case_id         UUID NOT NULL,
    title           VARCHAR(240) NOT NULL,
    status          VARCHAR(32) NOT NULL DEFAULT 'TODO',
    owner_membership_id UUID,
    due_at          TIMESTAMPTZ,
    priority        VARCHAR(16) NOT NULL DEFAULT 'MEDIA',
    blocked_reason  TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ,
    UNIQUE (id, tenant_id),
    FOREIGN KEY (case_id, tenant_id) REFERENCES app.legal_case (id, tenant_id),
    FOREIGN KEY (owner_membership_id, tenant_id) REFERENCES app.membership (id, tenant_id)
);

CREATE TABLE app.case_exception (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES control.tenant (id),
    case_id         UUID NOT NULL,
    title           VARCHAR(240) NOT NULL,
    exception_type  VARCHAR(64),
    severity        VARCHAR(16) NOT NULL
        CHECK (severity IN ('CRITICA', 'ALTA', 'MEDIA', 'BAJA')),
    owner_membership_id UUID,
    status          VARCHAR(32) NOT NULL DEFAULT 'OPEN',
    detail          TEXT,
    why             TEXT,
    evidence        TEXT,
    resolution      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id, tenant_id),
    FOREIGN KEY (case_id, tenant_id) REFERENCES app.legal_case (id, tenant_id),
    FOREIGN KEY (owner_membership_id, tenant_id) REFERENCES app.membership (id, tenant_id)
);

CREATE TABLE app.case_action (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES control.tenant (id),
    case_id         UUID NOT NULL,
    title           VARCHAR(240) NOT NULL,
    action_type     VARCHAR(64),
    status          VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    acted_at        TIMESTAMPTZ,
    actor_user_id   UUID REFERENCES app.app_user (id),
    evidence        TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id, tenant_id),
    FOREIGN KEY (case_id, tenant_id) REFERENCES app.legal_case (id, tenant_id)
);

CREATE TABLE app.sla_policy (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES control.tenant (id),
    code            VARCHAR(64) NOT NULL,
    name            VARCHAR(160) NOT NULL,
    hours_limit     INTEGER NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id, tenant_id),
    UNIQUE (tenant_id, code)
);

CREATE TABLE app.sla_clock (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES control.tenant (id),
    case_id         UUID NOT NULL,
    policy_id       UUID NOT NULL,
    started_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    due_at          TIMESTAMPTZ NOT NULL,
    stopped_at      TIMESTAMPTZ,
    UNIQUE (id, tenant_id),
    FOREIGN KEY (case_id, tenant_id) REFERENCES app.legal_case (id, tenant_id),
    FOREIGN KEY (policy_id, tenant_id) REFERENCES app.sla_policy (id, tenant_id)
);

CREATE TABLE app.case_acl (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES control.tenant (id),
    case_id         UUID NOT NULL,
    membership_id   UUID NOT NULL,
    can_read        BOOLEAN NOT NULL DEFAULT TRUE,
    can_write       BOOLEAN NOT NULL DEFAULT FALSE,
    can_assign      BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (id, tenant_id),
    UNIQUE (case_id, membership_id),
    FOREIGN KEY (case_id, tenant_id) REFERENCES app.legal_case (id, tenant_id),
    FOREIGN KEY (membership_id, tenant_id) REFERENCES app.membership (id, tenant_id)
);
