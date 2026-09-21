-- LEXIA-18 R4: calendario operacional y feriados para SLA.

CREATE TABLE app.operational_calendar (
    tenant_id           UUID PRIMARY KEY REFERENCES control.tenant (id),
    sla_mode            VARCHAR(24) NOT NULL DEFAULT 'CONTINUOUS'
        CHECK (sla_mode IN ('CONTINUOUS', 'BUSINESS_HOURS')),
    business_day_start  SMALLINT NOT NULL DEFAULT 8 CHECK (business_day_start BETWEEN 0 AND 23),
    business_day_end    SMALLINT NOT NULL DEFAULT 18 CHECK (business_day_end BETWEEN 1 AND 24),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (business_day_end > business_day_start)
);

CREATE TABLE app.operational_calendar_holiday (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL REFERENCES control.tenant (id),
    holiday_date DATE NOT NULL,
    label       VARCHAR(160) NOT NULL,
    UNIQUE (tenant_id, holiday_date)
);

INSERT INTO app.operational_calendar (tenant_id, sla_mode, business_day_start, business_day_end)
VALUES ('b1000000-0000-7000-8000-000000000001', 'CONTINUOUS', 8, 18)
ON CONFLICT (tenant_id) DO NOTHING;

INSERT INTO app.sla_policy (tenant_id, code, name, hours_limit)
SELECT 'b1000000-0000-7000-8000-000000000001', 'DEFAULT', 'SLA operacional (demo)', 72
WHERE NOT EXISTS (
    SELECT 1 FROM app.sla_policy p
    WHERE p.tenant_id = 'b1000000-0000-7000-8000-000000000001' AND p.code = 'DEFAULT'
);
