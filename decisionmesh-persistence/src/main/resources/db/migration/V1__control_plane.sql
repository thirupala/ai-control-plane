-- ============================================================
-- V1__complete_decisionmesh_schema.sql
-- ============================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ============================================================
-- UTILITIES
-- ============================================================

CREATE OR REPLACE FUNCTION fn_set_updated_at()
    RETURNS TRIGGER
    LANGUAGE plpgsql AS
$$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION fn_guard_immutable()
    RETURNS TRIGGER
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'Immutable record violation: table=% id=%', TG_TABLE_NAME, OLD.id;
END;
$$;

-- ============================================================
-- CORE: TENANTS / USERS / ORGS
-- ============================================================

CREATE TABLE tenants
(
    id          UUID PRIMARY KEY             DEFAULT gen_random_uuid(),
    external_id VARCHAR(255) UNIQUE NOT NULL,
    name        VARCHAR(255)        NOT NULL,
    status      VARCHAR(50)         NOT NULL DEFAULT 'ACTIVE',
    config      JSONB                        DEFAULT '{}',
    created_at  TIMESTAMPTZ                  DEFAULT now(),
    updated_at  TIMESTAMPTZ                  DEFAULT now()
);

CREATE TABLE organizations
(
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  UUID         NOT NULL REFERENCES tenants (id),
    name       VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ      DEFAULT now()
);

CREATE TABLE users
(
    user_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    external_user_id VARCHAR(255) UNIQUE,
    email            VARCHAR(255) UNIQUE,
    name             VARCHAR(255),
    created_at       TIMESTAMPTZ      DEFAULT now()
);

CREATE TABLE user_organizations
(
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID REFERENCES users (user_id),
    organization_id UUID REFERENCES organizations (id),
    tenant_id       UUID REFERENCES tenants (id),
    role            VARCHAR(100),
    created_at      TIMESTAMPTZ      DEFAULT now()
);

-- ============================================================
-- API KEYS
-- ============================================================

CREATE TABLE api_keys
(
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID REFERENCES tenants (id),
    organization_id UUID REFERENCES organizations (id),
    key_hash        VARCHAR(255) UNIQUE,
    key_prefix      VARCHAR(20),
    created_at      TIMESTAMPTZ      DEFAULT now()
);

-- ============================================================
-- ADAPTERS
-- ============================================================

CREATE TABLE adapters
(
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID REFERENCES tenants (id),
    name         VARCHAR(255),
    adapter_type VARCHAR(100),
    provider     VARCHAR(100),
    model_id     VARCHAR(255),
    is_active    BOOLEAN          DEFAULT TRUE,
    created_at   TIMESTAMPTZ      DEFAULT now(),

    CONSTRAINT uq_adapter UNIQUE (tenant_id, name, provider, model_id)
);

-- ============================================================
-- INTENTS
-- ============================================================

CREATE TABLE intents
(
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID REFERENCES tenants (id),
    user_id         UUID REFERENCES users (user_id),
    idempotency_key VARCHAR(255),
    intent_type     VARCHAR(100),
    phase           VARCHAR(50),
    objective       JSONB,
    version         INT              DEFAULT 0,
    created_at      TIMESTAMPTZ      DEFAULT now(),

    CONSTRAINT uq_intent_idem UNIQUE (tenant_id, idempotency_key)
);

-- ============================================================
-- PLANS
-- ============================================================

CREATE TABLE intent_plans
(
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    intent_id    UUID REFERENCES intents (id),
    tenant_id    UUID REFERENCES tenants (id),
    plan_version INT              DEFAULT 1
);

CREATE TABLE intent_plan_steps
(
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    plan_id    UUID REFERENCES intent_plans (id),
    intent_id  UUID REFERENCES intents (id),
    tenant_id  UUID REFERENCES tenants (id),
    adapter_id UUID REFERENCES adapters (id),
    step_order INT
);

-- ============================================================
-- EXECUTION
-- ============================================================

CREATE TABLE execution_records
(
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    intent_id   UUID REFERENCES intents (id),
    tenant_id   UUID REFERENCES tenants (id),
    adapter_id  UUID REFERENCES adapters (id),
    status      VARCHAR(50),
    cost_usd    NUMERIC(12, 6),
    latency_ms  BIGINT,
    executed_at TIMESTAMPTZ      DEFAULT now()
);

CREATE TABLE spend_records
(
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    intent_id    UUID REFERENCES intents (id),
    execution_id UUID REFERENCES execution_records (id),
    tenant_id    UUID REFERENCES tenants (id),
    amount_usd   NUMERIC(12, 6)
);

CREATE TABLE sla_windows
(
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    intent_id   UUID REFERENCES intents (id),
    tenant_id   UUID REFERENCES tenants (id),
    deadline_ms BIGINT
);

CREATE TABLE intent_drift_evaluations
(
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    intent_id    UUID REFERENCES intents (id),
    execution_id UUID REFERENCES execution_records (id),
    tenant_id    UUID REFERENCES tenants (id),
    drift_score  NUMERIC(5, 4)
);

-- ============================================================
-- POLICY
-- ============================================================

CREATE TABLE policies
(
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID REFERENCES tenants (id),
    name        VARCHAR(255),
    policy_type VARCHAR(100)
);

CREATE TABLE policy_evaluations
(
    id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    intent_id UUID REFERENCES intents (id),
    policy_id UUID REFERENCES policies (id),
    tenant_id UUID REFERENCES tenants (id),
    result    VARCHAR(50)
);

-- ============================================================
-- LEARNING
-- ============================================================

CREATE TABLE adapter_performance_profiles
(
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    adapter_id UUID REFERENCES adapters (id),
    tenant_id  UUID REFERENCES tenants (id)
);

CREATE TABLE adapter_profile_versions
(
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    profile_id UUID REFERENCES adapter_performance_profiles (id),
    tenant_id  UUID REFERENCES tenants (id)
);

-- ============================================================
-- RATE LIMIT
-- ============================================================

CREATE TABLE rate_limit_configs
(
    id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID REFERENCES tenants (id)
);

CREATE TABLE rate_limit_counters
(
    id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    config_id UUID REFERENCES rate_limit_configs (id),
    tenant_id UUID REFERENCES tenants (id)
);

-- ============================================================
-- OBSERVABILITY (FINAL VERSION)
-- ============================================================

CREATE TABLE intent_events
(
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id       UUID UNIQUE NOT NULL,
    intent_id      UUID REFERENCES intents (id),
    tenant_id      UUID REFERENCES tenants (id),
    version        BIGINT           DEFAULT 0,
    event_type     VARCHAR(255),
    aggregate_type VARCHAR(255)     DEFAULT 'Intent',
    occurred_at    TIMESTAMPTZ      DEFAULT now(),
    payload        JSONB            DEFAULT '{}'
);

CREATE TABLE audit_log
(
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID REFERENCES tenants (id),
    entity_type VARCHAR(100),
    entity_id   UUID,
    action      VARCHAR(100),
    occurred_at TIMESTAMPTZ      DEFAULT now()
);

-- ============================================================
-- IDEMPOTENCY (FIXED)
-- ============================================================

CREATE TABLE tenant_idempotency
(
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID         NOT NULL REFERENCES tenants (id),
    idempotency_key VARCHAR(255) NOT NULL,
    created_at      TIMESTAMPTZ      DEFAULT now(),

    CONSTRAINT uq_tenant_idempotency UNIQUE (tenant_id, idempotency_key)
);

-- ============================================================
-- DONE
-- ============================================================