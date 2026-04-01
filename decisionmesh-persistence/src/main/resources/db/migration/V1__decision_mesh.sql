-- ============================================================
-- V1__complete_decisionmesh_schema.sql
-- Single-file schema — drop and recreate in dev as needed.
-- ============================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ============================================================
-- UTILITY FUNCTIONS
-- Fix 1: fn_guard_immutable was defined twice — keep only the
--        correct version with proper UPDATE/DELETE handling.
-- ============================================================

CREATE OR REPLACE FUNCTION fn_set_updated_at()
    RETURNS TRIGGER LANGUAGE plpgsql AS
$$
BEGIN
    NEW.updated_at = now();
RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION fn_guard_immutable()
    RETURNS TRIGGER LANGUAGE plpgsql AS
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
$$;

-- ============================================================
-- CORE: TENANTS / USERS / ORGANISATIONS
-- ============================================================

CREATE TABLE tenants
(
    id              UUID PRIMARY KEY     DEFAULT gen_random_uuid(),
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
    id          UUID PRIMARY KEY     DEFAULT gen_random_uuid(),
    tenant_id   UUID        NOT NULL REFERENCES tenants (id),
    name        VARCHAR(255) NOT NULL,
    description TEXT,
    config      JSONB                DEFAULT '{}',
    is_active   BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ          DEFAULT now(),
    updated_at  TIMESTAMPTZ          DEFAULT now()
);

CREATE TABLE users
(
    user_id          UUID PRIMARY KEY    DEFAULT gen_random_uuid(),
    external_user_id VARCHAR(255) UNIQUE,
    email            VARCHAR(255) UNIQUE,
    name             VARCHAR(255),
    is_active        BOOLEAN    NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ         DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE user_organizations
(
    id              UUID PRIMARY KEY    DEFAULT gen_random_uuid(),
    user_id         UUID REFERENCES users (user_id),
    organization_id UUID REFERENCES organizations (id),
    tenant_id       UUID REFERENCES tenants (id),
    role            VARCHAR(100),
    permissions     JSONB      NOT NULL DEFAULT '[]',
    is_active       BOOLEAN    NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ         DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ============================================================
-- UI SUPPORT: ORGANISATIONS / BRANDING / PROJECTS / MEMBERS
-- Fix 13: these tables were missing entirely.
-- ============================================================

-- Simplified org record keyed by tenantId (mirrors OrgEntity.java)
CREATE TABLE organisations
(
    id         UUID PRIMARY KEY,               -- same as tenant_id
    name       VARCHAR(255) NOT NULL,
    plan       VARCHAR(20)  NOT NULL DEFAULT 'FREE',
    created_at TIMESTAMPTZ          DEFAULT now()
);

CREATE TABLE org_branding
(
    tenant_id     UUID PRIMARY KEY,
    org_name      VARCHAR(255),
    primary_color VARCHAR(7)   DEFAULT '#2563eb',
    logo_url      TEXT,
    favicon       TEXT,
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE projects
(
    id          UUID PRIMARY KEY,
    tenant_id   UUID         NOT NULL,
    name        VARCHAR(255) NOT NULL,
    description TEXT,
    environment VARCHAR(50)  NOT NULL DEFAULT 'Production',
    is_default  BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ           DEFAULT now()
);

CREATE INDEX idx_projects_tenant ON projects (tenant_id);

CREATE TABLE members
(
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID        NOT NULL,
    project_id     UUID,                       -- NULL = org-level member
    user_id        UUID        NOT NULL,
    name           VARCHAR(255),
    email          VARCHAR(255),
    role           VARCHAR(20) NOT NULL DEFAULT 'VIEWER',
    joined_at      TIMESTAMPTZ          DEFAULT now(),
    last_active_at TIMESTAMPTZ,
    UNIQUE (tenant_id, user_id, project_id)
);

CREATE INDEX idx_members_tenant     ON members (tenant_id);
CREATE INDEX idx_members_project    ON members (project_id);
CREATE INDEX idx_members_user_id    ON members (user_id);

CREATE TABLE invitations
(
    id         UUID PRIMARY KEY,
    tenant_id  UUID         NOT NULL,
    project_id UUID,                           -- NULL = org-level invitation
    email      VARCHAR(255) NOT NULL,
    role       VARCHAR(20)  NOT NULL DEFAULT 'VIEWER',
    status     VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    token      VARCHAR(64)  NOT NULL UNIQUE,
    created_at TIMESTAMPTZ           DEFAULT now(),
    expires_at TIMESTAMPTZ
);

CREATE INDEX idx_invitations_tenant ON invitations (tenant_id);
CREATE INDEX idx_invitations_token  ON invitations (token);
CREATE INDEX idx_invitations_email  ON invitations (email);

-- ============================================================
-- API KEYS
-- Fix 4: added user_id, name, scopes, expires_at,
--        last_used_at, revoked_at to match ApiKeyEntity.java
-- ============================================================

CREATE TABLE api_keys
(
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID REFERENCES tenants (id),
    organization_id UUID REFERENCES organizations (id),
    user_id         UUID,
    name            VARCHAR(255),
    key_hash        VARCHAR(255) UNIQUE,
    key_prefix      VARCHAR(20),
    scopes          TEXT,                      -- comma-separated
    created_at      TIMESTAMPTZ      DEFAULT now(),
    expires_at      TIMESTAMPTZ,
    last_used_at    TIMESTAMPTZ,
    revoked_at      TIMESTAMPTZ
);

CREATE INDEX idx_api_keys_tenant   ON api_keys (tenant_id);
CREATE INDEX idx_api_keys_user_id  ON api_keys (user_id);
CREATE INDEX idx_api_keys_key_hash ON api_keys (key_hash);

-- ============================================================
-- ADAPTERS
-- Fix 7: added type, endpoint, model_name, api_key_ref
--        to match AdapterEntity.java alongside existing columns.
-- ============================================================

CREATE TABLE adapters
(
    id                   UUID PRIMARY KEY     DEFAULT gen_random_uuid(),
    tenant_id            UUID        NOT NULL REFERENCES tenants (id),
    name                 VARCHAR(255) NOT NULL,
    -- Java entity fields (AdapterEntity.java)
    type                 VARCHAR(100),         -- OPENAI, ANTHROPIC, AZURE_OPENAI, CUSTOM
    endpoint             TEXT,
    model_name           VARCHAR(255),
    api_key_ref          VARCHAR(255),
    -- Extended domain fields (from original schema)
    adapter_type         VARCHAR(100)
        CHECK (adapter_type IN (
                                'LLM','EMBEDDING','TOOL','RETRIEVAL',
                                'RERANKER','CLASSIFIER','CUSTOM')),
    provider             VARCHAR(100),
    model_id             VARCHAR(255),
    region               VARCHAR(100),
    base_cost_per_token  NUMERIC(18, 8),
    max_tokens_per_call  INT,
    avg_latency_ms       BIGINT,
    config               JSONB       NOT NULL DEFAULT '{}',
    capability_flags     JSONB       NOT NULL DEFAULT '{}',
    allowed_intent_types JSONB       NOT NULL DEFAULT '[]',
    is_active            BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_adapters_tenant   ON adapters (tenant_id);
CREATE INDEX idx_adapters_type     ON adapters (tenant_id, adapter_type);
CREATE INDEX idx_adapters_provider ON adapters (tenant_id, provider);
CREATE INDEX idx_adapters_active   ON adapters (tenant_id, is_active);

CREATE TRIGGER trg_adapters_updated_at
    BEFORE UPDATE ON adapters
    FOR EACH ROW EXECUTE FUNCTION fn_set_updated_at();

-- ============================================================
-- INTENTS
-- Fix 2: removed DROP TABLE — pointless in a DROP+CREATE cycle.
-- Fix 9: injection_risk folded directly into CREATE TABLE.
-- ============================================================

CREATE TABLE intents
(
    id                 UUID PRIMARY KEY,
    tenant_id          UUID         NOT NULL,
    user_id            UUID,
    intent_type        VARCHAR(255) NOT NULL,
    phase              VARCHAR(50)  NOT NULL,
    satisfaction_state VARCHAR(50)  NOT NULL DEFAULT 'UNKNOWN',
    retry_count        INTEGER      NOT NULL DEFAULT 0,
    max_retries        INTEGER      NOT NULL DEFAULT 0,
    terminal           BOOLEAN      NOT NULL DEFAULT FALSE,
    version            BIGINT       NOT NULL DEFAULT 0,
    payload            JSONB        NOT NULL,
    injection_risk     NUMERIC(5, 4)          DEFAULT 0,
    created_at         TIMESTAMPTZ  NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_intents_tenant       ON intents (tenant_id, created_at DESC);
CREATE INDEX idx_intents_tenant_phase ON intents (tenant_id, phase, created_at DESC);
CREATE INDEX idx_intents_terminal     ON intents (tenant_id, terminal) WHERE terminal = FALSE;
CREATE INDEX idx_intent_injection     ON intents (injection_risk) WHERE injection_risk > 0.5;

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
-- Fix 9: response_text, quality_score, etc. folded into CREATE TABLE.
-- ============================================================

CREATE TABLE execution_records
(
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    intent_id             UUID REFERENCES intents (id),
    tenant_id             UUID REFERENCES tenants (id),
    adapter_id            UUID REFERENCES adapters (id),
    status                VARCHAR(50),
    cost_usd              NUMERIC(12, 6),
    latency_ms            BIGINT,
    response_text         TEXT,
    quality_score         NUMERIC(5, 4),
    hallucination_risk    NUMERIC(5, 4),
    hallucination_detected BOOLEAN         DEFAULT FALSE,
    quality_reasoning     VARCHAR(500),
    executed_at           TIMESTAMPTZ      DEFAULT now()
);

CREATE INDEX idx_exec_hallucination ON execution_records (hallucination_detected, adapter_id)
    WHERE hallucination_detected = TRUE;
CREATE INDEX idx_exec_quality       ON execution_records (quality_score, adapter_id)
    WHERE quality_score IS NOT NULL;

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
-- POLICIES
-- Fix 5: added policy_id, type, condition, action,
--        is_active, priority, updated_at to match PolicyEntity.java
-- ============================================================

CREATE TABLE policies
(
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    policy_id   VARCHAR(255),                 -- business key
    tenant_id   UUID REFERENCES tenants (id),
    name        VARCHAR(255),
    type        VARCHAR(50),                  -- PRE_SUBMISSION, PRE_EXECUTION, POST_EXECUTION
    policy_type VARCHAR(100),                 -- original domain field
    condition   TEXT,
    action      VARCHAR(50),                  -- BLOCK, WARN, LOG
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
    priority    INTEGER      NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ           DEFAULT now(),
    updated_at  TIMESTAMPTZ           DEFAULT now(),
    UNIQUE (policy_id, tenant_id)
);

CREATE INDEX idx_policies_tenant ON policies (tenant_id);

CREATE TABLE policy_evaluations
(
    id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    intent_id UUID REFERENCES intents (id),
    policy_id UUID REFERENCES policies (id),
    tenant_id UUID REFERENCES tenants (id),
    result    VARCHAR(50)
);

-- ============================================================
-- LEARNING / ADAPTER PERFORMANCE
-- ============================================================

CREATE TABLE adapter_performance_profiles
(
    id                   UUID PRIMARY KEY       DEFAULT gen_random_uuid(),
    adapter_id           UUID          NOT NULL REFERENCES adapters (id),
    tenant_id            UUID          NOT NULL REFERENCES tenants (id),
    ema_cost             NUMERIC(12,6) NOT NULL DEFAULT 0 CHECK (ema_cost >= 0),
    ema_latency_ms       NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (ema_latency_ms >= 0),
    ema_success_rate     NUMERIC(5,4)  NOT NULL DEFAULT 1 CHECK (ema_success_rate BETWEEN 0 AND 1),
    ema_risk_score       NUMERIC(5,4)  NOT NULL DEFAULT 0 CHECK (ema_risk_score BETWEEN 0 AND 1),
    ema_confidence       NUMERIC(5,4)  NOT NULL DEFAULT 0 CHECK (ema_confidence BETWEEN 0 AND 1),
    composite_score      NUMERIC(8,6)  NOT NULL DEFAULT 0 CHECK (composite_score >= 0),
    execution_count      BIGINT        NOT NULL DEFAULT 0 CHECK (execution_count >= 0),
    success_count        BIGINT        NOT NULL DEFAULT 0 CHECK (success_count >= 0),
    failure_count        BIGINT        NOT NULL DEFAULT 0 CHECK (failure_count >= 0),
    cold_start           BOOLEAN       NOT NULL DEFAULT TRUE,
    cold_start_threshold INT           NOT NULL DEFAULT 10 CHECK (cold_start_threshold > 0),
    is_degraded          BOOLEAN       NOT NULL DEFAULT FALSE,
    degraded_since       TIMESTAMPTZ,
    degraded_reason      VARCHAR(255),
    last_executed_at     TIMESTAMPTZ,
    version              INT           NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_profile_adapter_tenant UNIQUE (adapter_id, tenant_id)
);

CREATE INDEX idx_profile_tenant    ON adapter_performance_profiles (tenant_id);
CREATE INDEX idx_profile_composite ON adapter_performance_profiles (tenant_id, composite_score DESC)
    WHERE is_degraded = FALSE;
CREATE INDEX idx_profile_degraded  ON adapter_performance_profiles (tenant_id, is_degraded)
    WHERE is_degraded = TRUE;

CREATE TRIGGER trg_profile_updated_at
    BEFORE UPDATE ON adapter_performance_profiles
    FOR EACH ROW EXECUTE FUNCTION fn_set_updated_at();

CREATE TABLE adapter_profile_versions
(
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    profile_id UUID REFERENCES adapter_performance_profiles (id),
    tenant_id  UUID REFERENCES tenants (id)
);

-- ============================================================
-- RATE LIMITING
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
-- INTENT EVENTS (immutable event store)
-- ============================================================

CREATE TABLE intent_events
(
    id                   UUID PRIMARY KEY     DEFAULT gen_random_uuid(),
    event_id             UUID        NOT NULL,
    intent_id            UUID        NOT NULL,
    tenant_id            UUID        NOT NULL,
    version              BIGINT      NOT NULL,
    event_type           VARCHAR(255) NOT NULL,
    aggregate_type       VARCHAR(255) NOT NULL DEFAULT 'Intent',
    occurred_at          TIMESTAMPTZ NOT NULL,
    payload              JSONB       NOT NULL, -- no DEFAULT: missing payload is a bug
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
    drift_score_snapshot NUMERIC(5,4),
    cost_usd_snapshot    NUMERIC(12,6),
    risk_score_snapshot  NUMERIC(5,4),
    trace_id             VARCHAR(64),
    span_id              VARCHAR(64),
    parent_span_id       VARCHAR(64),
    CONSTRAINT uq_intent_events_event_id UNIQUE (event_id),
    CONSTRAINT uq_intent_events_version  UNIQUE (intent_id, version)
);

CREATE INDEX idx_events_intent        ON intent_events (intent_id, occurred_at ASC);
CREATE INDEX idx_events_intent_version ON intent_events (intent_id, version);
CREATE INDEX idx_events_tenant_time   ON intent_events (tenant_id, occurred_at DESC);
CREATE INDEX idx_events_type          ON intent_events (tenant_id, event_type, occurred_at DESC);
CREATE INDEX idx_events_trace         ON intent_events (tenant_id, trace_id) WHERE trace_id IS NOT NULL;

CREATE TRIGGER trg_intent_events_no_update
    BEFORE UPDATE ON intent_events
    FOR EACH ROW EXECUTE FUNCTION fn_guard_immutable();

CREATE TRIGGER trg_intent_events_no_delete
    BEFORE DELETE ON intent_events
    FOR EACH ROW EXECUTE FUNCTION fn_guard_immutable();

-- ============================================================
-- AUDIT LOG
-- Fix 6: added user_id(varchar), resource_type, resource_id,
--        outcome, detail to match AuditEntity.java +
--        kept original entity_type / entity_id columns.
-- ============================================================

CREATE TABLE audit_log
(
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID REFERENCES tenants (id),
    user_id       VARCHAR(255),               -- Keycloak subject (not FK)
    entity_type   VARCHAR(100),               -- original domain field
    entity_id     UUID,                        -- original domain field
    resource_type VARCHAR(100),               -- Java entity field
    resource_id   VARCHAR(255),               -- Java entity field
    action        VARCHAR(100),
    outcome       VARCHAR(20)      DEFAULT 'SUCCESS',
    detail        TEXT,
    occurred_at   TIMESTAMPTZ      DEFAULT now()
);

CREATE INDEX idx_audit_tenant      ON audit_log (tenant_id);
CREATE INDEX idx_audit_occurred_at ON audit_log (occurred_at DESC);
CREATE INDEX idx_audit_resource    ON audit_log (resource_type, resource_id);

-- ============================================================
-- IDEMPOTENCY
-- ============================================================

CREATE TABLE tenant_idempotency
(
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID         NOT NULL REFERENCES tenants (id),
    idempotency_key VARCHAR(255) NOT NULL,
    created_at      TIMESTAMPTZ      DEFAULT now(),
    CONSTRAINT uq_tenant_idempotency UNIQUE (tenant_id, idempotency_key)
);

CREATE INDEX idx_tenant_idempotency_key    ON tenant_idempotency (idempotency_key);
CREATE INDEX idx_tenant_idempotency_tenant ON tenant_idempotency (tenant_id);

-- ============================================================
-- GOVERNANCE: LEDGER / POLICY SNAPSHOT / PROCESSED EVENTS
-- Fix 8: processed_event → processed_events (matches Java entity @Table)
-- ============================================================

CREATE TABLE ledger_entry
(
    id                   UUID PRIMARY KEY,
    intent_id            UUID,
    tenant_id            VARCHAR(255),
    aggregate_version    BIGINT,
    event_id             UUID,
    event_type           VARCHAR(255),
    policy_snapshot_json TEXT,
    budget_snapshot_json TEXT,
    sla_snapshot_json    TEXT,
    previous_hash        VARCHAR(255),
    current_hash         VARCHAR(255),
    timestamp            TIMESTAMPTZ
);

CREATE INDEX idx_ledger_intent ON ledger_entry (intent_id);

CREATE TABLE policy_snapshot
(
    id            UUID PRIMARY KEY,
    intent_id     UUID,
    version       BIGINT,
    snapshot_json TEXT
);

-- Fix 8: was processed_event (no 's') — Java @Table(name="processed_events")
CREATE TABLE processed_events
(
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id     VARCHAR(255) NOT NULL UNIQUE,
    processed_at TIMESTAMPTZ      DEFAULT now()
);

CREATE INDEX idx_processed_events_event_id ON processed_events (event_id);

-- ============================================================
-- BILLING: CREDIT LEDGER / SUBSCRIPTION / BILLING CUSTOMER
-- Fix 3: subscription CREATE TABLE had broken parenthesis formatting.
-- ============================================================

CREATE TABLE credit_ledger
(
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id       UUID         NOT NULL,
    amount       INTEGER      NOT NULL,
    reason       VARCHAR(30)  NOT NULL,
    reference_id VARCHAR(255),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_credit_ledger_org_id     ON credit_ledger (org_id);
CREATE INDEX idx_credit_ledger_created_at ON credit_ledger (created_at);
CREATE INDEX idx_credit_ledger_reason     ON credit_ledger (reason);

COMMENT ON TABLE  credit_ledger              IS 'Append-only ledger — positive=credit, negative=debit';
COMMENT ON COLUMN credit_ledger.reason       IS 'REGISTRATION_GIFT|SUBSCRIPTION|PURCHASE|REFERRAL|INTENT_EXECUTION|RETRY|REFUND|ADMIN_ADJUSTMENT';
COMMENT ON COLUMN credit_ledger.reference_id IS 'intent_id for executions, stripe session_id for purchases';

CREATE TABLE subscription
(
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id                 UUID        NOT NULL,
    stripe_customer_id     VARCHAR(255),
    stripe_subscription_id VARCHAR(255),
    plan                   VARCHAR(20) NOT NULL DEFAULT 'FREE',
    status                 VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_subscription_org_id     ON subscription (org_id);
CREATE INDEX idx_subscription_stripe_sub ON subscription (stripe_subscription_id);

CREATE TABLE billing_customer
(
    org_id             UUID PRIMARY KEY,
    stripe_customer_id VARCHAR(255) NOT NULL UNIQUE
);

CREATE INDEX idx_billing_customer_stripe ON billing_customer (stripe_customer_id);

-- ============================================================
-- OBSERVABILITY / EXPLAINABILITY / DECISION TRACING
-- ============================================================

CREATE TABLE decision_traces
(
    decision_id       UUID PRIMARY KEY,
    intent_id         UUID        NOT NULL,
    tenant_id         VARCHAR     NOT NULL,
    decision_type     VARCHAR     NOT NULL,
    inputs_snapshot   JSONB,
    scoring_snapshot  JSONB,
    policy_snapshot   JSONB,
    portfolio_context JSONB,
    rationale         TEXT,
    timestamp         TIMESTAMPTZ NOT NULL
);

CREATE TABLE decision_trace_links
(
    parent_decision_id UUID NOT NULL,
    child_decision_id  UUID NOT NULL,
    PRIMARY KEY (parent_decision_id, child_decision_id)
);

CREATE TABLE intent_evaluations
(
    intent_id          UUID             NOT NULL,
    satisfaction_score DOUBLE PRECISION NOT NULL,
    drift_score        DOUBLE PRECISION NOT NULL,
    evaluated_at       TIMESTAMPTZ      NOT NULL
);

-- ============================================================
-- EVENTSOURCING / OUTBOX / MULTI-REGION / PORTFOLIO
-- ============================================================

CREATE TABLE event_outbox
(
    id             UUID PRIMARY KEY,
    aggregate_type VARCHAR     NOT NULL,
    aggregate_id   UUID        NOT NULL,
    event_type     VARCHAR     NOT NULL,
    payload_json   JSONB       NOT NULL,
    published      BOOLEAN     DEFAULT FALSE,
    created_at     TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_event_outbox_published ON event_outbox (published, created_at) WHERE published = FALSE;

CREATE TABLE consumer_offsets
(
    consumer_id  VARCHAR     NOT NULL,
    event_id     UUID        NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (consumer_id, event_id)
);

CREATE TABLE intent_dependencies
(
    parent_intent_id UUID        NOT NULL,
    child_intent_id  UUID        NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (parent_intent_id, child_intent_id)
);

CREATE TABLE intent_region_registry
(
    intent_id       UUID PRIMARY KEY,
    tenant_id       VARCHAR     NOT NULL,
    home_region     VARCHAR     NOT NULL,
    failover_region VARCHAR,
    updated_at      TIMESTAMPTZ NOT NULL
);

CREATE TABLE global_idempotency
(
    idempotency_key VARCHAR PRIMARY KEY,
    created_at      TIMESTAMPTZ NOT NULL
);

CREATE TABLE lifecycle_audit
(
    intent_id  UUID        NOT NULL,
    phase      VARCHAR     NOT NULL,
    action     VARCHAR     NOT NULL,
    version    BIGINT      NOT NULL,
    timestamp  TIMESTAMPTZ NOT NULL
);

CREATE TABLE drift_tracking
(
    intent_id  UUID PRIMARY KEY,
    last_drift DOUBLE PRECISION NOT NULL,
    updated_at TIMESTAMPTZ      NOT NULL
);

CREATE TABLE exploration_ledger
(
    entry_id    UUID PRIMARY KEY,
    adapter_id  VARCHAR          NOT NULL,
    intent_type VARCHAR          NOT NULL,
    exploration BOOLEAN          NOT NULL,
    reward      DOUBLE PRECISION NOT NULL,
    regret      DOUBLE PRECISION NOT NULL,
    confidence  DOUBLE PRECISION NOT NULL,
    timestamp   TIMESTAMPTZ      NOT NULL
);

-- ============================================================
-- DONE
-- ============================================================
