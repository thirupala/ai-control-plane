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
    id              UUID PRIMARY KEY             DEFAULT gen_random_uuid(),
    organization_id UUID,
    external_id     VARCHAR(255) UNIQUE NOT NULL,
    name            VARCHAR(255)        NOT NULL,
    status          VARCHAR(50)         NOT NULL DEFAULT 'ACTIVE',
    config          JSONB                        DEFAULT '{}',
    created_at      TIMESTAMPTZ                  DEFAULT now(),
    updated_at      TIMESTAMPTZ                  DEFAULT now()
);

CREATE TABLE organizations
(
    id          UUID PRIMARY KEY      DEFAULT gen_random_uuid(),
    tenant_id   UUID         NOT NULL REFERENCES tenants (id),
    name        VARCHAR(255) NOT NULL,
    description TEXT,
    config      JSONB                 DEFAULT '{}',
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ           DEFAULT now(),
    updated_at  TIMESTAMPTZ           DEFAULT now()
);

CREATE TABLE users
(
    user_id          UUID PRIMARY KEY     DEFAULT gen_random_uuid(),
    external_user_id VARCHAR(255) UNIQUE,
    email            VARCHAR(255) UNIQUE,
    name             VARCHAR(255),
    is_active        BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ          DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE user_organizations
(
    id              UUID PRIMARY KEY     DEFAULT gen_random_uuid(),
    user_id         UUID REFERENCES users (user_id),
    organization_id UUID REFERENCES organizations (id),
    tenant_id       UUID REFERENCES tenants (id),
    role            VARCHAR(100),
    permissions     JSONB       NOT NULL DEFAULT '[]',
    is_active       BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ          DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
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
    id                   UUID PRIMARY KEY      DEFAULT gen_random_uuid(),
    tenant_id            UUID         NOT NULL REFERENCES tenants (id),
    name                 VARCHAR(255) NOT NULL,
    adapter_type         VARCHAR(100) NOT NULL
        CHECK (adapter_type IN (
                                'LLM', 'EMBEDDING', 'TOOL', 'RETRIEVAL',
                                'RERANKER', 'CLASSIFIER', 'CUSTOM')),
    provider             VARCHAR(100) NOT NULL,
    model_id             VARCHAR(255),
    region               VARCHAR(100),
    base_cost_per_token  NUMERIC(18, 8),
    max_tokens_per_call  INT,
    avg_latency_ms       BIGINT,
    config               JSONB        NOT NULL DEFAULT '{}',
    capability_flags     JSONB        NOT NULL DEFAULT '{}',
    allowed_intent_types JSONB        NOT NULL DEFAULT '[]',
    is_active            BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_adapters_tenant ON adapters (tenant_id);
CREATE INDEX idx_adapters_type ON adapters (tenant_id, adapter_type);
CREATE INDEX idx_adapters_provider ON adapters (tenant_id, provider);
CREATE INDEX idx_adapters_active ON adapters (tenant_id, is_active);

CREATE TRIGGER trg_adapters_updated_at
    BEFORE UPDATE
    ON adapters
    FOR EACH ROW
EXECUTE FUNCTION fn_set_updated_at();

-- ============================================================
-- INTENTS
-- ============================================================

-- Drop the incomplete table
DROP TABLE IF EXISTS intents;

CREATE TABLE intents
(
    id                 UUID         PRIMARY KEY,
    tenant_id          UUID         NOT NULL,
    user_id            UUID,
    intent_type        VARCHAR(255) NOT NULL,
    phase              VARCHAR(50)  NOT NULL,
    satisfaction_state VARCHAR(50)  NOT NULL DEFAULT 'UNKNOWN',
    retry_count        INTEGER      NOT NULL DEFAULT 0,
    max_retries        INTEGER      NOT NULL DEFAULT 0,
    terminal           BOOLEAN      NOT NULL DEFAULT false,
    version            BIGINT       NOT NULL DEFAULT 0,
    payload            JSONB        NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_intents_tenant
    ON intents (tenant_id, created_at DESC);
CREATE INDEX idx_intents_tenant_phase
    ON intents (tenant_id, phase, created_at DESC);
CREATE INDEX idx_intents_terminal
    ON intents (tenant_id, terminal)
    WHERE terminal = false;

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
    id                   UUID PRIMARY KEY        DEFAULT gen_random_uuid(),
    adapter_id           UUID           NOT NULL REFERENCES adapters (id),
    tenant_id            UUID           NOT NULL REFERENCES tenants (id),
    ema_cost             NUMERIC(12, 6) NOT NULL DEFAULT 0 CHECK (ema_cost >= 0),
    ema_latency_ms       NUMERIC(12, 2) NOT NULL DEFAULT 0 CHECK (ema_latency_ms >= 0),
    ema_success_rate     NUMERIC(5, 4)  NOT NULL DEFAULT 1 CHECK (ema_success_rate BETWEEN 0 AND 1),
    ema_risk_score       NUMERIC(5, 4)  NOT NULL DEFAULT 0 CHECK (ema_risk_score BETWEEN 0 AND 1),
    ema_confidence       NUMERIC(5, 4)  NOT NULL DEFAULT 0 CHECK (ema_confidence BETWEEN 0 AND 1),
    composite_score      NUMERIC(8, 6)  NOT NULL DEFAULT 0 CHECK (composite_score >= 0),
    execution_count      BIGINT         NOT NULL DEFAULT 0 CHECK (execution_count >= 0),
    success_count        BIGINT         NOT NULL DEFAULT 0 CHECK (success_count >= 0),
    failure_count        BIGINT         NOT NULL DEFAULT 0 CHECK (failure_count >= 0),
    cold_start           BOOLEAN        NOT NULL DEFAULT TRUE,
    cold_start_threshold INT            NOT NULL DEFAULT 10 CHECK (cold_start_threshold > 0),
    is_degraded          BOOLEAN        NOT NULL DEFAULT FALSE,
    degraded_since       TIMESTAMPTZ,
    degraded_reason      VARCHAR(255),
    last_executed_at     TIMESTAMPTZ,
    version              INT            NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT uq_profile_adapter_tenant UNIQUE (adapter_id, tenant_id)
);

CREATE INDEX idx_profile_tenant ON adapter_performance_profiles (tenant_id);
CREATE INDEX idx_profile_composite ON adapter_performance_profiles (tenant_id, composite_score DESC)
    WHERE is_degraded = FALSE;
CREATE INDEX idx_profile_degraded ON adapter_performance_profiles (tenant_id, is_degraded)
    WHERE is_degraded = TRUE;

CREATE TRIGGER trg_profile_updated_at
    BEFORE UPDATE
    ON adapter_performance_profiles
    FOR EACH ROW
EXECUTE FUNCTION fn_set_updated_at();

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
CREATE OR REPLACE FUNCTION fn_guard_immutable()
    RETURNS TRIGGER AS
$$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'Immutable record violation: table=% id=% (P0001)',
            TG_TABLE_NAME, OLD.id;
    ELSIF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Immutable record violation: table=% id=% (P0001)',
            TG_TABLE_NAME, OLD.id;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;
CREATE TABLE intent_events
(
    id                   UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id             UUID        NOT NULL,
    intent_id            UUID        NOT NULL,
    tenant_id            UUID        NOT NULL,
    version              BIGINT      NOT NULL,
    event_type           VARCHAR(255) NOT NULL,
    aggregate_type       VARCHAR(255) NOT NULL DEFAULT 'Intent',
    occurred_at          TIMESTAMPTZ  NOT NULL,

    -- No DEFAULT: a missing payload is a bug and should fail loudly
    payload              JSONB        NOT NULL,

    phase_from           VARCHAR(50),
    phase_to             VARCHAR(50),
    actor_id             UUID,
    actor_type           VARCHAR(100),
    plan_id              UUID,
    plan_version         INTEGER,
    execution_id         UUID,
    attempt_number       INTEGER,
    adapter_id           UUID,
    policy_id            UUID,
    drift_score_snapshot NUMERIC(5,  4),
    cost_usd_snapshot    NUMERIC(12, 6),
    risk_score_snapshot  NUMERIC(5,  4),
    trace_id             VARCHAR(64),
    span_id              VARCHAR(64),
    parent_span_id       VARCHAR(64),

    -- Idempotency: one physical event row per logical event
    CONSTRAINT uq_intent_events_event_id   UNIQUE (event_id),

    -- Optimistic concurrency: one version per aggregate stream
    CONSTRAINT uq_intent_events_version    UNIQUE (intent_id, version)
);


-- ─── Indexes ─────────────────────────────────────────────────────────────────

-- Aggregate stream replay (most common read path)
CREATE INDEX idx_events_intent
    ON intent_events (intent_id, occurred_at ASC);

-- Version conflict checks during writes
CREATE INDEX idx_events_intent_version
    ON intent_events (intent_id, version);

-- Tenant-scoped time-range queries
CREATE INDEX idx_events_tenant_time
    ON intent_events (tenant_id, occurred_at DESC);

-- Event type filtering within a tenant
CREATE INDEX idx_events_type
    ON intent_events (tenant_id, event_type, occurred_at DESC);

-- Distributed trace lookups scoped to tenant (avoids full cross-tenant scan)
CREATE INDEX idx_events_trace
    ON intent_events (tenant_id, trace_id)
    WHERE trace_id IS NOT NULL;


-- ─── Immutability triggers ───────────────────────────────────────────────────

CREATE TRIGGER trg_intent_events_no_update
    BEFORE UPDATE
    ON intent_events
    FOR EACH ROW
EXECUTE FUNCTION fn_guard_immutable();

CREATE TRIGGER trg_intent_events_no_delete
    BEFORE DELETE
    ON intent_events
    FOR EACH ROW
EXECUTE FUNCTION fn_guard_immutable();

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
CREATE INDEX idx_tenant_idempotency_key ON tenant_idempotency (idempotency_key);
CREATE INDEX idx_tenant_idempotency_tenant ON tenant_idempotency (tenant_id);

-- ============================================================
-- DONE
-- ============================================================