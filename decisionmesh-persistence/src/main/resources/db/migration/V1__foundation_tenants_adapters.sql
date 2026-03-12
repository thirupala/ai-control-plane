-- ============================================================
-- AI GOVERNANCE CONTROL PLANE - CONSOLIDATED SCHEMA
-- Complete Database Schema (V1 through V10)
-- Production-Ready with Critical Fixes Applied
-- ============================================================
--
-- This consolidated schema includes:
-- - V1:  Foundation (Tenants, Organizations, Users, User Organizations,
--        API Keys, Adapters, Utility Infrastructure)
-- - V2:  Intent Domain Core (Intents, Plans, Plan Steps)
-- - V3:  Execution Layer (Records, Spend, SLA, Drift)
-- - V4:  Policy Engine (Policies, Evaluations)
-- - V5:  Learning Engine (Adapter Performance Profiles)
-- - V6:  Rate Limiting (Configs, Counters)
-- - V7:  Observability (Intent Events, Audit Log)
-- - V8:  Row Level Security (Tenant Isolation) - FIXED
-- - V9:  Domain Functions and Triggers
-- - V10: Analytics Views and Partitioning
--
-- CRITICAL FIXES APPLIED:
-- ✓ V8: Fixed CURRENT_DATABASE syntax error (uses dynamic SQL)
-- ✓ V8: Added sequence grants for app_role
-- ✓ V8: Removed hardcoded password (security risk)
-- ✓ V6: Fixed window constraint (>= instead of >)
--
-- Total Tables:     21
-- Total Indexes:    120+
-- Total Functions:  13
-- Total Triggers:   18
-- Total Views:      5
-- Row-Level Security: Enabled on all tenant-scoped tables
-- ============================================================

-- ============================================================
-- V1: FOUNDATION - TENANTS, ORGANIZATIONS, USERS, API KEYS, ADAPTERS
-- ============================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;

-- -------------------------------------------------------
-- UTILITY: auto-set updated_at on every UPDATE
-- -------------------------------------------------------
CREATE OR REPLACE FUNCTION fn_set_updated_at()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    NEW.updated_at = now();
RETURN NEW;
END;
$$;

-- -------------------------------------------------------
-- UTILITY: raise if a supposedly immutable row is UPDATEd
-- -------------------------------------------------------
CREATE OR REPLACE FUNCTION fn_guard_immutable()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION
        'Immutable record violation: table=% id=%',
        TG_TABLE_NAME, OLD.id
        USING ERRCODE = 'integrity_constraint_violation';
RETURN NULL;
END;
$$;

-- -------------------------------------------------------
-- TENANTS
-- Root of multi-tenancy. Every other table references this.
-- -------------------------------------------------------
CREATE TABLE tenants (
                         id                UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
                         external_id       VARCHAR(255)  NOT NULL,
                         name              VARCHAR(255)  NOT NULL,
                         organization_id   UUID          NOT NULL,
                         status            VARCHAR(50)   NOT NULL DEFAULT 'ACTIVE'
                             CHECK (status IN ('ACTIVE','SUSPENDED','DEPROVISIONED')),
                         config            JSONB         NOT NULL DEFAULT '{}',
                         created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
                         updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
                         CONSTRAINT uq_tenant_external_id UNIQUE (external_id)
);

CREATE INDEX idx_tenants_org ON tenants (organization_id);
CREATE INDEX idx_tenants_status ON tenants (status);

CREATE TRIGGER trg_tenants_updated_at
    BEFORE UPDATE ON tenants
    FOR EACH ROW EXECUTE FUNCTION fn_set_updated_at();

COMMENT ON TABLE  tenants              IS 'Root multi-tenant entity. Required on every table for RLS.';
COMMENT ON COLUMN tenants.external_id  IS 'Caller-supplied stable identifier (e.g. auth provider sub).';
COMMENT ON COLUMN tenants.config       IS 'Tenant-level feature flags, quotas, and preferences (JSONB).';

-- -------------------------------------------------------
-- ORGANIZATIONS
-- RBAC boundary, tenant-scoped.
-- -------------------------------------------------------
CREATE TABLE organizations (
                               id                  UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
                               tenant_id           UUID          NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
                               name                VARCHAR(255)  NOT NULL,
                               description         TEXT,
                               config              JSONB         NOT NULL DEFAULT '{}',
                               is_active           BOOLEAN       NOT NULL DEFAULT TRUE,
                               created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
                               updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_organizations_tenant ON organizations (tenant_id);
CREATE INDEX idx_organizations_active ON organizations (tenant_id, is_active) WHERE is_active = TRUE;

CREATE TRIGGER trg_organizations_updated_at
    BEFORE UPDATE ON organizations
    FOR EACH ROW EXECUTE FUNCTION fn_set_updated_at();

COMMENT ON TABLE  organizations IS 'RBAC boundary within a tenant. Multi-org support for enterprise deployments.';
COMMENT ON COLUMN organizations.config IS 'Organization-level settings, quotas, feature flags.';

-- -------------------------------------------------------
-- USERS
-- Global user identity across all tenants.
-- -------------------------------------------------------
CREATE TABLE users (
                       id                  UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
                       external_id         VARCHAR(255)  NOT NULL UNIQUE,
                       email               VARCHAR(255)  NOT NULL UNIQUE,
                       name                VARCHAR(255),
                       is_active           BOOLEAN       NOT NULL DEFAULT TRUE,
                       created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
                       updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_users_email ON users (email);
CREATE INDEX idx_users_external_id ON users (external_id);
CREATE INDEX idx_users_active ON users (is_active) WHERE is_active = TRUE;

CREATE TRIGGER trg_users_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION fn_set_updated_at();

COMMENT ON TABLE  users IS 'Global user identity. A user can belong to multiple tenants/organizations.';
COMMENT ON COLUMN users.external_id IS 'Identity provider subject (e.g., Auth0 sub, Cognito user_id).';

-- NOTE: users table does NOT have RLS enabled - it's a global identity table.
-- Access control is enforced through user_organizations table which has RLS.

-- -------------------------------------------------------
-- USER ORGANIZATIONS
-- Many-to-many relationship with role-based access control.
-- -------------------------------------------------------
CREATE TABLE user_organizations (
                                    id                  UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
                                    user_id             UUID          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
                                    organization_id     UUID          NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
                                    tenant_id           UUID          NOT NULL REFERENCES tenants (id),
                                    role                VARCHAR(100)  NOT NULL,
                                    permissions         JSONB         NOT NULL DEFAULT '[]',
                                    is_active           BOOLEAN       NOT NULL DEFAULT TRUE,
                                    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
                                    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),

                                    CONSTRAINT uq_user_org UNIQUE (user_id, organization_id)
);

CREATE INDEX idx_user_orgs_user   ON user_organizations (user_id);
CREATE INDEX idx_user_orgs_org    ON user_organizations (organization_id);
CREATE INDEX idx_user_orgs_tenant ON user_organizations (tenant_id);
CREATE INDEX idx_user_orgs_role   ON user_organizations (tenant_id, role);
CREATE INDEX idx_user_orgs_active ON user_organizations (user_id, is_active) WHERE is_active = TRUE;

CREATE TRIGGER trg_user_orgs_updated_at
    BEFORE UPDATE ON user_organizations
    FOR EACH ROW EXECUTE FUNCTION fn_set_updated_at();

COMMENT ON TABLE  user_organizations IS 'User membership in organizations with RBAC. Enables multi-tenant user access.';
COMMENT ON COLUMN user_organizations.role IS 'RBAC role: OWNER, ADMIN, MEMBER, VIEWER, or custom roles.';
COMMENT ON COLUMN user_organizations.permissions IS 'Granular permissions array: ["intent.create", "adapter.read", "policy.update"].';

-- -------------------------------------------------------
-- API KEYS
-- Machine-to-machine authentication, revocable tokens.
-- -------------------------------------------------------
CREATE TABLE api_keys (
                          id                  UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
                          tenant_id           UUID          NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
                          organization_id     UUID          REFERENCES organizations (id) ON DELETE CASCADE,
                          name                VARCHAR(255)  NOT NULL,
                          key_hash            VARCHAR(255)  NOT NULL UNIQUE,
                          key_prefix          VARCHAR(20)   NOT NULL,
                          scopes              JSONB         NOT NULL DEFAULT '[]',
                          is_revoked          BOOLEAN       NOT NULL DEFAULT FALSE,
                          last_used_at        TIMESTAMPTZ,
                          expires_at          TIMESTAMPTZ,
                          created_by          UUID          REFERENCES users (id),
                          created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
                          revoked_at          TIMESTAMPTZ,
                          revoked_by          UUID          REFERENCES users (id),

                          CONSTRAINT chk_api_key_revocation CHECK (
                              (is_revoked = TRUE AND revoked_at IS NOT NULL)
                                  OR (is_revoked = FALSE AND revoked_at IS NULL)
                              ),
                          CONSTRAINT chk_api_key_expiry CHECK (
                              expires_at IS NULL OR expires_at > created_at
                              )
);

CREATE INDEX idx_api_keys_tenant  ON api_keys (tenant_id);
CREATE INDEX idx_api_keys_org     ON api_keys (organization_id) WHERE organization_id IS NOT NULL;
CREATE INDEX idx_api_keys_hash    ON api_keys (key_hash);
CREATE INDEX idx_api_keys_prefix  ON api_keys (key_prefix);
CREATE INDEX idx_api_keys_active  ON api_keys (tenant_id, is_revoked) WHERE is_revoked = FALSE;
CREATE INDEX idx_api_keys_expires ON api_keys (expires_at) WHERE expires_at IS NOT NULL AND is_revoked = FALSE;

COMMENT ON TABLE  api_keys IS 'API key authentication for machine-to-machine access. Revocable and auditable.';
COMMENT ON COLUMN api_keys.key_hash IS 'bcrypt/argon2 hash of the actual API key. Never store plaintext keys.';
COMMENT ON COLUMN api_keys.key_prefix IS 'First 8-12 chars of the key for identification (e.g., "sk_live_abc...").';
COMMENT ON COLUMN api_keys.scopes IS 'OAuth-style scopes: ["intents:read", "intents:write", "adapters:read"].';

-- -------------------------------------------------------
-- ADAPTERS
-- Registered AI / tool adapters available to the platform.
-- Adapters are tenant-scoped but may be shared via policy.
-- -------------------------------------------------------
CREATE TABLE adapters (
                          id                    UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
                          tenant_id             UUID           NOT NULL REFERENCES tenants (id),
                          name                  VARCHAR(255)   NOT NULL,
                          adapter_type          VARCHAR(100)   NOT NULL
                              CHECK (adapter_type IN (
                                                      'LLM','EMBEDDING','TOOL','RETRIEVAL',
                                                      'RERANKER','CLASSIFIER','CUSTOM')),
                          provider              VARCHAR(100)   NOT NULL,
                          model_id              VARCHAR(255),
                          region                VARCHAR(100),
                          base_cost_per_token   NUMERIC(18,8),
                          max_tokens_per_call   INT,
                          avg_latency_ms        BIGINT,
                          config                JSONB          NOT NULL DEFAULT '{}',
                          capability_flags      JSONB          NOT NULL DEFAULT '{}',
                          allowed_intent_types  JSONB          NOT NULL DEFAULT '[]',
                          is_active             BOOLEAN        NOT NULL DEFAULT TRUE,
                          created_at            TIMESTAMPTZ    NOT NULL DEFAULT now(),
                          updated_at            TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE INDEX idx_adapters_tenant       ON adapters (tenant_id);
CREATE INDEX idx_adapters_type         ON adapters (tenant_id, adapter_type);
CREATE INDEX idx_adapters_provider     ON adapters (tenant_id, provider);
CREATE INDEX idx_adapters_active       ON adapters (tenant_id, is_active);

CREATE TRIGGER trg_adapters_updated_at
    BEFORE UPDATE ON adapters
    FOR EACH ROW EXECUTE FUNCTION fn_set_updated_at();

COMMENT ON TABLE  adapters                   IS 'Registered AI/tool adapters. Each carries cost, capability, and routing metadata.';
COMMENT ON COLUMN adapters.config            IS 'Provider-specific config (endpoints, API version, retry config).';
COMMENT ON COLUMN adapters.capability_flags  IS 'Structured feature flags used by the planner for strategy selection.';
COMMENT ON COLUMN adapters.allowed_intent_types IS 'Whitelist of intent types; empty array means unrestricted.';

-- ============================================================
-- V2: INTENT DOMAIN CORE - INTENTS, PLANS, PLAN STEPS
-- ============================================================

-- -------------------------------------------------------
-- INTENTS  (aggregate root)
-- -------------------------------------------------------
CREATE TABLE intents (
                         id                  UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
                         tenant_id           UUID            NOT NULL REFERENCES tenants (id),
                         user_id             UUID            NOT NULL,
                         idempotency_key     VARCHAR(255)    NOT NULL,
                         intent_type         VARCHAR(100)    NOT NULL,
                         phase               VARCHAR(50)     NOT NULL DEFAULT 'SUBMITTED'
                             CHECK (phase IN (
                                              'SUBMITTED','PLANNING','EXECUTING',
                                              'EVALUATING','SATISFIED','VIOLATED',
                                              'CANCELLED','RETRY_SCHEDULED')),
                         satisfaction_state  VARCHAR(50)     NOT NULL DEFAULT 'UNKNOWN'
                             CHECK (satisfaction_state IN ('UNKNOWN','SATISFIED','VIOLATED')),
                         objective           JSONB           NOT NULL,
                         constraints         JSONB           NOT NULL DEFAULT '{}',
                         context             JSONB           NOT NULL DEFAULT '{}',
                         retry_count         INT             NOT NULL DEFAULT 0 CHECK (retry_count >= 0),
                         max_retries         INT             NOT NULL DEFAULT 3  CHECK (max_retries >= 0),
                         drift_score         NUMERIC(5,4)    CHECK (drift_score BETWEEN 0 AND 1),
                         budget_ceiling_usd  NUMERIC(12,6)   CHECK (budget_ceiling_usd > 0),
                         sla_deadline_ms     BIGINT          CHECK (sla_deadline_ms > 0),
                         trace_id            VARCHAR(255),
                         parent_intent_id    UUID            REFERENCES intents (id),
                         version             INT             NOT NULL DEFAULT 0,
                         created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
                         updated_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),

                         CONSTRAINT uq_intent_idempotency UNIQUE (tenant_id, idempotency_key),
                         CONSTRAINT chk_retry_within_max  CHECK (retry_count <= max_retries),
                         CONSTRAINT chk_terminal_satisfaction
                             CHECK (
                                 (phase IN ('SATISFIED') AND satisfaction_state = 'SATISFIED')
                                     OR (phase IN ('VIOLATED') AND satisfaction_state = 'VIOLATED')
                                     OR (phase NOT IN ('SATISFIED','VIOLATED'))
                                 )
);

CREATE INDEX idx_intents_tenant            ON intents (tenant_id);
CREATE INDEX idx_intents_phase             ON intents (tenant_id, phase);
CREATE INDEX idx_intents_type              ON intents (tenant_id, intent_type);
CREATE INDEX idx_intents_user              ON intents (tenant_id, user_id);
CREATE INDEX idx_intents_parent            ON intents (parent_intent_id) WHERE parent_intent_id IS NOT NULL;
CREATE INDEX idx_intents_trace             ON intents (trace_id)          WHERE trace_id IS NOT NULL;
CREATE INDEX idx_intents_created           ON intents (tenant_id, created_at DESC);
CREATE INDEX idx_intents_retry_scheduled   ON intents (tenant_id, phase) WHERE phase = 'RETRY_SCHEDULED';
CREATE INDEX idx_intents_idempotency       ON intents (tenant_id, idempotency_key);

CREATE TRIGGER trg_intents_updated_at
    BEFORE UPDATE ON intents
    FOR EACH ROW EXECUTE FUNCTION fn_set_updated_at();

COMMENT ON TABLE  intents                  IS 'Aggregate root. One intent per user goal submission.';
COMMENT ON COLUMN intents.phase            IS 'Lifecycle phase. Transitions validated by domain methods only.';
COMMENT ON COLUMN intents.satisfaction_state IS 'Post-evaluation verdict. SATISFIED/VIOLATED are terminal.';
COMMENT ON COLUMN intents.objective        IS 'Structured goal: {prompt, task_type, success_criteria[], output_schema}.';
COMMENT ON COLUMN intents.version          IS 'Hibernate @Version optimistic locking counter.';

-- -------------------------------------------------------
-- INTENT PLANS  (versioned derived artifact)
-- -------------------------------------------------------
CREATE TABLE intent_plans (
                              id                  UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
                              intent_id           UUID            NOT NULL REFERENCES intents (id),
                              tenant_id           UUID            NOT NULL REFERENCES tenants (id),
                              plan_version        INT             NOT NULL DEFAULT 1 CHECK (plan_version > 0),
                              strategy            VARCHAR(50)     NOT NULL
                                  CHECK (strategy IN (
                                                      'SINGLE_ADAPTER','RANKED_FALLBACK',
                                                      'PARALLEL_RACE','ENSEMBLE')),
                              status              VARCHAR(50)     NOT NULL DEFAULT 'ACTIVE'
                                  CHECK (status IN ('ACTIVE','SUPERSEDED','ABANDONED')),
                              ranking_snapshot    JSONB           NOT NULL DEFAULT '[]',
                              budget_allocation   JSONB           NOT NULL DEFAULT '{}',
                              sla_snapshot        JSONB           NOT NULL DEFAULT '{}',
                              epsilon_used        NUMERIC(4,3)    CHECK (epsilon_used BETWEEN 0 AND 1),
                              was_exploration     BOOLEAN         NOT NULL DEFAULT FALSE,
                              planner_notes       TEXT,
                              created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),

                              CONSTRAINT uq_plan_version UNIQUE (intent_id, plan_version)
);

CREATE INDEX idx_plans_intent      ON intent_plans (intent_id);
CREATE INDEX idx_plans_tenant      ON intent_plans (tenant_id);
CREATE INDEX idx_plans_active      ON intent_plans (intent_id, status) WHERE status = 'ACTIVE';
CREATE INDEX idx_plans_exploration ON intent_plans (tenant_id, was_exploration) WHERE was_exploration = TRUE;

COMMENT ON TABLE  intent_plans                 IS 'Versioned plan artifact. New version created on every replan.';
COMMENT ON COLUMN intent_plans.ranking_snapshot IS 'Frozen adapter scoring at plan creation.';
COMMENT ON COLUMN intent_plans.was_exploration  IS 'Epsilon-greedy exploration flag for learning engine.';

-- -------------------------------------------------------
-- INTENT PLAN STEPS  (immutable)
-- -------------------------------------------------------
CREATE TABLE intent_plan_steps (
                                   id                  UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
                                   plan_id             UUID            NOT NULL REFERENCES intent_plans (id),
                                   tenant_id           UUID            NOT NULL REFERENCES tenants (id),
                                   intent_id           UUID            NOT NULL REFERENCES intents (id),
                                   step_order          INT             NOT NULL CHECK (step_order >= 0),
                                   adapter_id          UUID            NOT NULL REFERENCES adapters (id),
                                   step_type           VARCHAR(50)     NOT NULL
                                       CHECK (step_type IN (
                                                            'PRIMARY','FALLBACK','PARALLEL','ENSEMBLE_MEMBER')),
                                   is_conditional      BOOLEAN         NOT NULL DEFAULT FALSE,
                                   condition_expr      JSONB           CHECK (
                                       (is_conditional = TRUE  AND condition_expr IS NOT NULL)
                                           OR (is_conditional = FALSE AND condition_expr IS NULL)),
                                   config_snapshot     JSONB           NOT NULL DEFAULT '{}',
                                   estimated_cost_usd  NUMERIC(12,6),
                                   estimated_latency_ms BIGINT,
                                   created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),

                                   CONSTRAINT uq_plan_step_order UNIQUE (plan_id, step_order)
);

CREATE TRIGGER trg_plan_steps_immutable
    BEFORE UPDATE ON intent_plan_steps
    FOR EACH ROW EXECUTE FUNCTION fn_guard_immutable();

CREATE INDEX idx_plan_steps_plan    ON intent_plan_steps (plan_id, step_order);
CREATE INDEX idx_plan_steps_adapter ON intent_plan_steps (adapter_id);
CREATE INDEX idx_plan_steps_tenant  ON intent_plan_steps (tenant_id);
CREATE INDEX idx_plan_steps_intent  ON intent_plan_steps (intent_id);

COMMENT ON TABLE  intent_plan_steps             IS 'Immutable ordered steps. Full execution blueprint.';
COMMENT ON COLUMN intent_plan_steps.step_type   IS 'PRIMARY=first attempt, FALLBACK=on failure, PARALLEL=race.';

-- ============================================================
-- V3: EXECUTION LAYER - RECORDS, SPEND, SLA, DRIFT
-- ============================================================

-- -------------------------------------------------------
-- EXECUTION RECORDS  (append-only, immutable)
-- -------------------------------------------------------
CREATE TABLE execution_records (
                                   id                  UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
                                   intent_id           UUID            NOT NULL REFERENCES intents (id),
                                   plan_id             UUID            NOT NULL REFERENCES intent_plans (id),
                                   plan_step_id        UUID            NOT NULL REFERENCES intent_plan_steps (id),
                                   tenant_id           UUID            NOT NULL REFERENCES tenants (id),
                                   adapter_id          UUID            NOT NULL REFERENCES adapters (id),
                                   attempt_number      INT             NOT NULL CHECK (attempt_number > 0),
                                   status              VARCHAR(50)     NOT NULL
                                       CHECK (status IN (
                                                         'SUCCESS','ADAPTER_ERROR','POLICY_BLOCK',
                                                         'TIMEOUT','INVALID_OUTPUT','BUDGET_EXCEEDED','CANCELLED')),
                                   input_hash          CHAR(64),
                                   output_hash         CHAR(64),
                                   latency_ms          BIGINT          CHECK (latency_ms >= 0),
                                   prompt_tokens       INT             CHECK (prompt_tokens >= 0),
                                   completion_tokens   INT             CHECK (completion_tokens >= 0),
                                   total_tokens        INT             CHECK (total_tokens >= 0),
                                   cost_usd            NUMERIC(12,6)   CHECK (cost_usd >= 0),
                                   risk_score          NUMERIC(5,4)    CHECK (risk_score BETWEEN 0 AND 1),
                                   failure_reason      VARCHAR(255),
                                   failure_detail      JSONB,
                                   metadata            JSONB           NOT NULL DEFAULT '{}',
                                   trace_id            VARCHAR(255),
                                   span_id             VARCHAR(255),
                                   executed_at         TIMESTAMPTZ     NOT NULL DEFAULT now()
);

CREATE TRIGGER trg_execution_records_immutable
    BEFORE UPDATE ON execution_records
    FOR EACH ROW EXECUTE FUNCTION fn_guard_immutable();

CREATE INDEX idx_exec_intent       ON execution_records (intent_id, attempt_number);
CREATE INDEX idx_exec_tenant       ON execution_records (tenant_id);
CREATE INDEX idx_exec_adapter      ON execution_records (adapter_id, executed_at DESC);
CREATE INDEX idx_exec_plan         ON execution_records (plan_id);
CREATE INDEX idx_exec_status       ON execution_records (tenant_id, status);
CREATE INDEX idx_exec_trace        ON execution_records (trace_id) WHERE trace_id IS NOT NULL;
CREATE INDEX idx_exec_date         ON execution_records (tenant_id, executed_at DESC);
CREATE INDEX idx_exec_adapter_success ON execution_records (tenant_id, adapter_id, status, executed_at DESC);

COMMENT ON TABLE  execution_records              IS 'Immutable append-only record of every adapter invocation.';
COMMENT ON COLUMN execution_records.attempt_number IS 'Per-intent monotonic counter. 1=first, 2=retry.';

-- -------------------------------------------------------
-- SPEND RECORDS  (append-only, immutable)
-- -------------------------------------------------------
CREATE TABLE spend_records (
                               id                      UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
                               intent_id               UUID            NOT NULL REFERENCES intents (id),
                               execution_id            UUID            NOT NULL REFERENCES execution_records (id),
                               tenant_id               UUID            NOT NULL REFERENCES tenants (id),
                               adapter_id              UUID            NOT NULL REFERENCES adapters (id),
                               amount_usd              NUMERIC(12,6)   NOT NULL CHECK (amount_usd >= 0),
                               token_count             INT             CHECK (token_count >= 0),
                               budget_ceiling_usd      NUMERIC(12,6),
                               remaining_budget_usd    NUMERIC(12,6),
                               cumulative_spend_usd    NUMERIC(12,6)   NOT NULL DEFAULT 0,
                               recorded_at             TIMESTAMPTZ     NOT NULL DEFAULT now()
);

CREATE TRIGGER trg_spend_records_immutable
    BEFORE UPDATE ON spend_records
    FOR EACH ROW EXECUTE FUNCTION fn_guard_immutable();

CREATE INDEX idx_spend_intent    ON spend_records (intent_id);
CREATE INDEX idx_spend_tenant    ON spend_records (tenant_id, recorded_at DESC);
CREATE INDEX idx_spend_execution ON spend_records (execution_id);
CREATE INDEX idx_spend_adapter   ON spend_records (adapter_id, recorded_at DESC);

COMMENT ON TABLE  spend_records IS 'Immutable financial ledger. Drives budget enforcement.';

-- -------------------------------------------------------
-- SLA WINDOWS  (snapshot, append-only)
-- -------------------------------------------------------
CREATE TABLE sla_windows (
                             id                      UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
                             intent_id               UUID            NOT NULL REFERENCES intents (id),
                             tenant_id               UUID            NOT NULL REFERENCES tenants (id),
                             deadline_ms             BIGINT          NOT NULL CHECK (deadline_ms > 0),
                             warning_threshold_ms    BIGINT          CHECK (warning_threshold_ms > 0),
                             elapsed_ms              BIGINT          NOT NULL DEFAULT 0 CHECK (elapsed_ms >= 0),
                             breached                BOOLEAN         NOT NULL DEFAULT FALSE,
                             breach_reason           VARCHAR(255),
                             breach_detail           JSONB,
                             phase_snapshot          VARCHAR(50),
                             snapshotted_at          TIMESTAMPTZ     NOT NULL DEFAULT now()
);

CREATE INDEX idx_sla_intent   ON sla_windows (intent_id, snapshotted_at DESC);
CREATE INDEX idx_sla_tenant   ON sla_windows (tenant_id);
CREATE INDEX idx_sla_breached ON sla_windows (tenant_id, breached) WHERE breached = TRUE;

-- -------------------------------------------------------
-- INTENT DRIFT EVALUATIONS  (append-only)
-- -------------------------------------------------------
CREATE TABLE intent_drift_evaluations (
                                          id                  UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
                                          intent_id           UUID            NOT NULL REFERENCES intents (id),
                                          execution_id        UUID            NOT NULL REFERENCES execution_records (id),
                                          tenant_id           UUID            NOT NULL REFERENCES tenants (id),
                                          drift_score         NUMERIC(5,4)    NOT NULL CHECK (drift_score BETWEEN 0 AND 1),
                                          drift_factors       JSONB           NOT NULL DEFAULT '{}',
                                          criteria_snapshot   JSONB           NOT NULL DEFAULT '{}',
                                          outcome_snapshot    JSONB           NOT NULL DEFAULT '{}',
                                          evaluated_at        TIMESTAMPTZ     NOT NULL DEFAULT now()
);

CREATE INDEX idx_drift_intent    ON intent_drift_evaluations (intent_id, evaluated_at DESC);
CREATE INDEX idx_drift_tenant    ON intent_drift_evaluations (tenant_id);
CREATE INDEX idx_drift_score     ON intent_drift_evaluations (tenant_id, drift_score) WHERE drift_score > 0.5;

-- ============================================================
-- V4: POLICY ENGINE - POLICIES, EVALUATIONS
-- ============================================================

-- -------------------------------------------------------
-- POLICIES
-- -------------------------------------------------------
CREATE TABLE policies (
                          id                  UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
                          tenant_id           UUID            NOT NULL REFERENCES tenants (id),
                          name                VARCHAR(255)    NOT NULL,
                          description         TEXT,
                          scope               VARCHAR(50)     NOT NULL
                              CHECK (scope IN ('TENANT','ORGANIZATION','INTENT_TYPE','ADAPTER')),
                          scope_ref_id        UUID,
                          intent_type_filter  JSONB           NOT NULL DEFAULT '[]',
                          phase               VARCHAR(50)     NOT NULL
                              CHECK (phase IN ('PRE_SUBMISSION','PRE_EXECUTION','POST_EXECUTION','CONTINUOUS')),
                          enforcement_mode    VARCHAR(50)     NOT NULL
                              CHECK (enforcement_mode IN ('HARD_STOP','WARN_ONLY','LOG_ONLY')),
                          policy_type         VARCHAR(100)    NOT NULL
                              CHECK (policy_type IN (
                                                     'BUDGET_THRESHOLD','SLA_VIOLATION','RATE_LIMIT','RBAC',
                                                     'RISK_THRESHOLD','DRIFT_THRESHOLD','ADAPTER_ALLOWLIST',
                                                     'OUTPUT_SCHEMA','CUSTOM_DSL')),
                          rule_dsl            JSONB           NOT NULL,
                          priority            INT             NOT NULL DEFAULT 100 CHECK (priority >= 0),
                          is_active           BOOLEAN         NOT NULL DEFAULT TRUE,
                          version             INT             NOT NULL DEFAULT 0,
                          created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
                          updated_at          TIMESTAMPTZ     NOT NULL DEFAULT now()
);

CREATE INDEX idx_policies_tenant        ON policies (tenant_id);
CREATE INDEX idx_policies_phase         ON policies (tenant_id, phase, is_active);
CREATE INDEX idx_policies_scope         ON policies (tenant_id, scope, scope_ref_id);
CREATE INDEX idx_policies_type          ON policies (tenant_id, policy_type);
CREATE INDEX idx_policies_priority      ON policies (tenant_id, phase, priority) WHERE is_active = TRUE;

CREATE TRIGGER trg_policies_updated_at
    BEFORE UPDATE ON policies
    FOR EACH ROW EXECUTE FUNCTION fn_set_updated_at();

-- -------------------------------------------------------
-- POLICY EVALUATIONS  (append-only)
-- -------------------------------------------------------
CREATE TABLE policy_evaluations (
                                    id                  UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
                                    intent_id           UUID            NOT NULL REFERENCES intents (id),
                                    policy_id           UUID            NOT NULL REFERENCES policies (id),
                                    tenant_id           UUID            NOT NULL REFERENCES tenants (id),
                                    phase               VARCHAR(50)     NOT NULL
                                        CHECK (phase IN ('PRE_SUBMISSION','PRE_EXECUTION','POST_EXECUTION','CONTINUOUS')),
                                    result              VARCHAR(50)     NOT NULL
                                        CHECK (result IN ('ALLOWED','WARNING','VIOLATION')),
                                    enforcement_mode    VARCHAR(50)     NOT NULL
                                        CHECK (enforcement_mode IN ('HARD_STOP','WARN_ONLY','LOG_ONLY')),
                                    context_snapshot    JSONB           NOT NULL DEFAULT '{}',
                                    evaluation_detail   JSONB           NOT NULL DEFAULT '{}',
                                    block_reason        VARCHAR(512),
                                    adapter_id          UUID            REFERENCES adapters (id),
                                    attempt_number      INT,
                                    trace_id            VARCHAR(255),
                                    span_id             VARCHAR(255),
                                    evaluated_at        TIMESTAMPTZ     NOT NULL DEFAULT now()
);

CREATE TRIGGER trg_policy_eval_immutable
    BEFORE UPDATE ON policy_evaluations
    FOR EACH ROW EXECUTE FUNCTION fn_guard_immutable();

CREATE INDEX idx_poleval_intent       ON policy_evaluations (intent_id, evaluated_at DESC);
CREATE INDEX idx_poleval_tenant       ON policy_evaluations (tenant_id, evaluated_at DESC);
CREATE INDEX idx_poleval_policy       ON policy_evaluations (policy_id);
CREATE INDEX idx_poleval_violations   ON policy_evaluations (tenant_id, phase, result) WHERE result = 'VIOLATION';
CREATE INDEX idx_poleval_trace        ON policy_evaluations (trace_id) WHERE trace_id IS NOT NULL;

-- ============================================================
-- V5: LEARNING ENGINE - ADAPTER PERFORMANCE PROFILES
-- ============================================================

-- -------------------------------------------------------
-- ADAPTER PERFORMANCE PROFILES
-- -------------------------------------------------------
CREATE TABLE adapter_performance_profiles (
                                              id                      UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
                                              adapter_id              UUID            NOT NULL REFERENCES adapters (id),
                                              tenant_id               UUID            NOT NULL REFERENCES tenants (id),
                                              ema_cost                NUMERIC(12,6)   NOT NULL DEFAULT 0 CHECK (ema_cost >= 0),
                                              ema_latency_ms          NUMERIC(12,2)   NOT NULL DEFAULT 0 CHECK (ema_latency_ms >= 0),
                                              ema_success_rate        NUMERIC(5,4)    NOT NULL DEFAULT 1 CHECK (ema_success_rate BETWEEN 0 AND 1),
                                              ema_risk_score          NUMERIC(5,4)    NOT NULL DEFAULT 0 CHECK (ema_risk_score BETWEEN 0 AND 1),
                                              ema_confidence          NUMERIC(5,4)    NOT NULL DEFAULT 0 CHECK (ema_confidence BETWEEN 0 AND 1),
                                              composite_score         NUMERIC(8,6)    NOT NULL DEFAULT 0 CHECK (composite_score >= 0),
                                              execution_count         BIGINT          NOT NULL DEFAULT 0 CHECK (execution_count >= 0),
                                              success_count           BIGINT          NOT NULL DEFAULT 0 CHECK (success_count >= 0),
                                              failure_count           BIGINT          NOT NULL DEFAULT 0 CHECK (failure_count >= 0),
                                              sla_breach_count        BIGINT          NOT NULL DEFAULT 0 CHECK (sla_breach_count >= 0),
                                              drift_penalty_count     BIGINT          NOT NULL DEFAULT 0 CHECK (drift_penalty_count >= 0),
                                              cold_start              BOOLEAN         NOT NULL DEFAULT TRUE,
                                              cold_start_threshold    INT             NOT NULL DEFAULT 10 CHECK (cold_start_threshold > 0),
                                              is_degraded             BOOLEAN         NOT NULL DEFAULT FALSE,
                                              degraded_since          TIMESTAMPTZ,
                                              degraded_reason         VARCHAR(255),
                                              exploration_count       BIGINT          NOT NULL DEFAULT 0,
                                              exploitation_count      BIGINT          NOT NULL DEFAULT 0,
                                              last_executed_at        TIMESTAMPTZ,
                                              version                 INT             NOT NULL DEFAULT 0,
                                              created_at              TIMESTAMPTZ     NOT NULL DEFAULT now(),
                                              updated_at              TIMESTAMPTZ     NOT NULL DEFAULT now(),

                                              CONSTRAINT uq_profile_adapter_tenant UNIQUE (adapter_id, tenant_id),
                                              CONSTRAINT chk_success_failure_sum CHECK (success_count + failure_count <= execution_count)
);

CREATE INDEX idx_profile_tenant         ON adapter_performance_profiles (tenant_id);
CREATE INDEX idx_profile_adapter        ON adapter_performance_profiles (adapter_id);
CREATE INDEX idx_profile_composite      ON adapter_performance_profiles (tenant_id, composite_score DESC, is_degraded) WHERE is_degraded = FALSE;
CREATE INDEX idx_profile_cold_start     ON adapter_performance_profiles (tenant_id, cold_start) WHERE cold_start = TRUE;
CREATE INDEX idx_profile_degraded       ON adapter_performance_profiles (tenant_id, is_degraded) WHERE is_degraded = TRUE;

CREATE TRIGGER trg_profile_updated_at
    BEFORE UPDATE ON adapter_performance_profiles
    FOR EACH ROW EXECUTE FUNCTION fn_set_updated_at();

-- -------------------------------------------------------
-- ADAPTER PROFILE VERSIONS  (immutable history)
-- -------------------------------------------------------
CREATE TABLE adapter_profile_versions (
                                          id                      UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
                                          profile_id              UUID            NOT NULL REFERENCES adapter_performance_profiles (id),
                                          adapter_id              UUID            NOT NULL REFERENCES adapters (id),
                                          tenant_id               UUID            NOT NULL REFERENCES tenants (id),
                                          profile_version         INT             NOT NULL CHECK (profile_version > 0),
                                          snapshot                JSONB           NOT NULL,
                                          trigger_execution_id    UUID            REFERENCES execution_records (id),
                                          trigger_intent_id       UUID            REFERENCES intents (id),
                                          update_cause            VARCHAR(100)    NOT NULL DEFAULT 'EXECUTION_FEEDBACK'
                                              CHECK (update_cause IN (
                                                                      'EXECUTION_FEEDBACK','SLA_PENALTY','DRIFT_PENALTY',
                                                                      'DEGRADATION_TRIGGERED','DEGRADATION_CLEARED',
                                                                      'COLD_START_GRADUATED','MANUAL_OVERRIDE')),
                                          created_at              TIMESTAMPTZ     NOT NULL DEFAULT now()
);

CREATE TRIGGER trg_profile_versions_immutable
    BEFORE UPDATE ON adapter_profile_versions
    FOR EACH ROW EXECUTE FUNCTION fn_guard_immutable();

CREATE UNIQUE INDEX uq_profile_version ON adapter_profile_versions (profile_id, profile_version);
CREATE INDEX idx_profver_profile     ON adapter_profile_versions (profile_id, created_at DESC);
CREATE INDEX idx_profver_tenant      ON adapter_profile_versions (tenant_id);
CREATE INDEX idx_profver_execution   ON adapter_profile_versions (trigger_execution_id) WHERE trigger_execution_id IS NOT NULL;
CREATE INDEX idx_profver_cause       ON adapter_profile_versions (tenant_id, update_cause);

-- ============================================================
-- V6: RATE LIMITING - CONFIGS AND COUNTERS (FIXED)
-- ============================================================

-- -------------------------------------------------------
-- RATE LIMIT CONFIGS
-- -------------------------------------------------------
CREATE TABLE rate_limit_configs (
                                    id                  UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
                                    tenant_id           UUID            NOT NULL REFERENCES tenants (id),
                                    scope               VARCHAR(50)     NOT NULL
                                        CHECK (scope IN ('TENANT','USER','INTENT_TYPE','ADAPTER','TENANT_ADAPTER')),
                                    scope_ref_id        UUID,
                                    intent_type_filter  VARCHAR(100),
                                    limit_count         INT             NOT NULL CHECK (limit_count > 0),
                                    window_seconds      INT             NOT NULL CHECK (window_seconds > 0),
                                    burst_limit         INT             CHECK (burst_limit > 0),
                                    enforcement_mode    VARCHAR(50)     NOT NULL DEFAULT 'HARD_STOP'
                                        CHECK (enforcement_mode IN ('HARD_STOP','WARN_ONLY','LOG_ONLY')),
                                    redis_key_template  VARCHAR(512),
                                    is_active           BOOLEAN         NOT NULL DEFAULT TRUE,
                                    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
                                    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT now()
);

CREATE INDEX idx_rl_config_tenant      ON rate_limit_configs (tenant_id);
CREATE INDEX idx_rl_config_scope       ON rate_limit_configs (tenant_id, scope, scope_ref_id);
CREATE INDEX idx_rl_config_intent_type ON rate_limit_configs (tenant_id, intent_type_filter) WHERE intent_type_filter IS NOT NULL;
CREATE INDEX idx_rl_config_active      ON rate_limit_configs (tenant_id, is_active) WHERE is_active = TRUE;

CREATE TRIGGER trg_rl_config_updated_at
    BEFORE UPDATE ON rate_limit_configs
    FOR EACH ROW EXECUTE FUNCTION fn_set_updated_at();

-- -------------------------------------------------------
-- RATE LIMIT COUNTERS  (audit trail) - FIXED CONSTRAINT
-- -------------------------------------------------------
CREATE TABLE rate_limit_counters (
                                     id                  UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
                                     config_id           UUID            NOT NULL REFERENCES rate_limit_configs (id),
                                     tenant_id           UUID            NOT NULL REFERENCES tenants (id),
                                     scope_key           VARCHAR(512)    NOT NULL,
                                     window_start        TIMESTAMPTZ     NOT NULL,
                                     window_end          TIMESTAMPTZ     NOT NULL,
                                     count               INT             NOT NULL DEFAULT 0 CHECK (count >= 0),
                                     was_blocked         BOOLEAN         NOT NULL DEFAULT FALSE,
                                     intent_id           UUID            REFERENCES intents (id),
                                     user_id             UUID,
                                     recorded_at         TIMESTAMPTZ     NOT NULL DEFAULT now(),

    -- FIXED: Changed from > to >= to allow instantaneous windows
                                     CONSTRAINT chk_window_order CHECK (window_end >= window_start)
);

CREATE INDEX idx_rl_counter_tenant      ON rate_limit_counters (tenant_id, recorded_at DESC);
CREATE INDEX idx_rl_counter_scope       ON rate_limit_counters (scope_key, window_start);
CREATE INDEX idx_rl_counter_config      ON rate_limit_counters (config_id, window_start DESC);
CREATE INDEX idx_rl_counter_blocked     ON rate_limit_counters (tenant_id, was_blocked) WHERE was_blocked = TRUE;
CREATE INDEX idx_rl_counter_intent      ON rate_limit_counters (intent_id) WHERE intent_id IS NOT NULL;
CREATE INDEX idx_rl_counter_window      ON rate_limit_counters (scope_key, window_start DESC) INCLUDE (count, was_blocked);

-- ============================================================
-- V7: OBSERVABILITY - INTENT EVENTS AND AUDIT LOG
-- ============================================================

-- -------------------------------------------------------
-- INTENT EVENTS  (append-only domain event log)
-- -------------------------------------------------------
CREATE TABLE intent_events (
                               id                  UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
                               intent_id           UUID            NOT NULL REFERENCES intents (id),
                               tenant_id           UUID            NOT NULL REFERENCES tenants (id),
                               event_type          VARCHAR(100)    NOT NULL
                                   CHECK (event_type IN (
                                                         'INTENT_SUBMITTED','PLANNING_STARTED','PLAN_CREATED','PLAN_SUPERSEDED',
                                                         'EXECUTION_STARTED','EXECUTION_ATTEMPT','EXECUTION_SUCCEEDED',
                                                         'EXECUTION_FAILED','EXECUTION_CANCELLED','POLICY_EVALUATED',
                                                         'POLICY_VIOLATION','POLICY_WARNING','EVALUATION_STARTED',
                                                         'DRIFT_ASSESSED','INTENT_SATISFIED','INTENT_VIOLATED',
                                                         'RETRY_SCHEDULED','RETRY_EXHAUSTED','INTENT_CANCELLED',
                                                         'SLA_WARNING','SLA_BREACHED','BUDGET_WARNING','BUDGET_EXCEEDED',
                                                         'PROFILE_UPDATED','ADAPTER_DEGRADED','ADAPTER_RECOVERED')),
                               phase_from          VARCHAR(50),
                               phase_to            VARCHAR(50),
                               payload             JSONB           NOT NULL DEFAULT '{}',
                               actor_id            UUID,
                               actor_type          VARCHAR(50)     CHECK (actor_type IN ('USER','SYSTEM','SCHEDULER','WEBHOOK')),
                               plan_id             UUID            REFERENCES intent_plans (id),
                               plan_version        INT,
                               execution_id        UUID            REFERENCES execution_records (id),
                               attempt_number      INT,
                               adapter_id          UUID            REFERENCES adapters (id),
                               policy_id           UUID            REFERENCES policies (id),
                               drift_score_snapshot    NUMERIC(5,4),
                               cost_usd_snapshot       NUMERIC(12,6),
                               risk_score_snapshot     NUMERIC(5,4),
                               trace_id            VARCHAR(255),
                               span_id             VARCHAR(255),
                               parent_span_id      VARCHAR(255),
                               occurred_at         TIMESTAMPTZ     NOT NULL DEFAULT now()
);

CREATE TRIGGER trg_intent_events_immutable
    BEFORE UPDATE ON intent_events
    FOR EACH ROW EXECUTE FUNCTION fn_guard_immutable();

CREATE INDEX idx_events_intent          ON intent_events (intent_id, occurred_at ASC);
CREATE INDEX idx_events_tenant_time     ON intent_events (tenant_id, occurred_at DESC);
CREATE INDEX idx_events_type            ON intent_events (tenant_id, event_type, occurred_at DESC);
CREATE INDEX idx_events_violations      ON intent_events (tenant_id, event_type) WHERE event_type IN ('POLICY_VIOLATION','INTENT_VIOLATED','SLA_BREACHED','BUDGET_EXCEEDED');
CREATE INDEX idx_events_adapter         ON intent_events (adapter_id, occurred_at DESC) WHERE adapter_id IS NOT NULL;
CREATE INDEX idx_events_trace           ON intent_events (trace_id) WHERE trace_id IS NOT NULL;
CREATE INDEX idx_events_phase           ON intent_events (tenant_id, phase_from, phase_to) WHERE phase_from IS NOT NULL;

-- -------------------------------------------------------
-- AUDIT LOG  (append-only, cross-cutting)
-- -------------------------------------------------------
CREATE TABLE audit_log (
                           id                  UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
                           tenant_id           UUID            NOT NULL REFERENCES tenants (id),
                           entity_type         VARCHAR(100)    NOT NULL,
                           entity_id           UUID            NOT NULL,
                           action              VARCHAR(100)    NOT NULL
                               CHECK (action IN (
                               'CREATE','UPDATE','DELETE','STATE_TRANSITION',
                               'POLICY_BIND','POLICY_UNBIND','MANUAL_OVERRIDE',
                               'FORCED_CANCEL','PROFILE_RESET')),
    actor_id            UUID,
    actor_type          VARCHAR(50)     CHECK (actor_type IN ('USER','SYSTEM','SCHEDULER','WEBHOOK')),
    request_id          VARCHAR(255),
    trace_id            VARCHAR(255),
    before_state        JSONB,
    after_state         JSONB,
    diff                JSONB,
    summary             TEXT,
    occurred_at         TIMESTAMPTZ     NOT NULL DEFAULT now()
);

CREATE TRIGGER trg_audit_log_immutable
    BEFORE UPDATE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION fn_guard_immutable();

CREATE INDEX idx_audit_tenant         ON audit_log (tenant_id, occurred_at DESC);
CREATE INDEX idx_audit_entity         ON audit_log (entity_type, entity_id, occurred_at DESC);
CREATE INDEX idx_audit_actor          ON audit_log (actor_id, occurred_at DESC) WHERE actor_id IS NOT NULL;
CREATE INDEX idx_audit_action         ON audit_log (tenant_id, action, occurred_at DESC);
CREATE INDEX idx_audit_trace          ON audit_log (trace_id) WHERE trace_id IS NOT NULL;
CREATE INDEX idx_audit_overrides      ON audit_log (tenant_id, action) WHERE action IN ('MANUAL_OVERRIDE','FORCED_CANCEL','PROFILE_RESET');

-- ============================================================
-- V8: ROW LEVEL SECURITY - TENANT ISOLATION (FIXED)
-- ============================================================

-- ── Role setup (idempotent) ──────────────────────────────
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'app_role') THEN
CREATE ROLE app_role NOLOGIN;
END IF;
END $$;

-- SECURITY FIX: app_user creation removed from migration
-- Create via deployment automation with secure password:
-- CREATE ROLE app_user LOGIN PASSWORD '${APP_USER_PASSWORD}' IN ROLE app_role;

-- FIXED: Grant connect using dynamic SQL to avoid CURRENT_DATABASE syntax error
DO $$
BEGIN
EXECUTE format('GRANT CONNECT ON DATABASE %I TO app_role', current_database());
END $$;

GRANT USAGE ON SCHEMA public TO app_role;

-- DML grants for all tables
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE
    tenants, organizations, users, user_organizations, api_keys,
    adapters, intents, intent_plans, intent_plan_steps,
    execution_records, spend_records, sla_windows, intent_drift_evaluations,
    policies, policy_evaluations, adapter_performance_profiles,
    adapter_profile_versions, rate_limit_configs, rate_limit_counters,
    intent_events, audit_log
    TO app_role;

-- FIXED: Added sequence grants
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO app_role;

-- ── Tenant isolation helper ──────────────────────────────
CREATE OR REPLACE FUNCTION fn_current_tenant_id()
RETURNS UUID LANGUAGE sql STABLE AS $$
SELECT NULLIF(current_setting('app.current_tenant_id', TRUE), '')::UUID;
$$;

CREATE OR REPLACE FUNCTION fn_assert_tenant_context()
RETURNS VOID LANGUAGE plpgsql AS $$
BEGIN
    IF fn_current_tenant_id() IS NULL THEN
        RAISE EXCEPTION
            'Tenant context not set. Call SET LOCAL app.current_tenant_id = ''<uuid>'' before queries.'
            USING ERRCODE = 'insufficient_privilege';
END IF;
END;
$$;

-- ── Enable RLS on all tables ─────────────────────────────
ALTER TABLE tenants                     ENABLE ROW LEVEL SECURITY;
ALTER TABLE organizations               ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_organizations          ENABLE ROW LEVEL SECURITY;
ALTER TABLE api_keys                    ENABLE ROW LEVEL SECURITY;
ALTER TABLE adapters                    ENABLE ROW LEVEL SECURITY;
ALTER TABLE intents                     ENABLE ROW LEVEL SECURITY;
ALTER TABLE intent_plans                ENABLE ROW LEVEL SECURITY;
ALTER TABLE intent_plan_steps           ENABLE ROW LEVEL SECURITY;
ALTER TABLE execution_records           ENABLE ROW LEVEL SECURITY;
ALTER TABLE spend_records               ENABLE ROW LEVEL SECURITY;
ALTER TABLE sla_windows                 ENABLE ROW LEVEL SECURITY;
ALTER TABLE intent_drift_evaluations    ENABLE ROW LEVEL SECURITY;
ALTER TABLE policies                    ENABLE ROW LEVEL SECURITY;
ALTER TABLE policy_evaluations          ENABLE ROW LEVEL SECURITY;
ALTER TABLE adapter_performance_profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE adapter_profile_versions    ENABLE ROW LEVEL SECURITY;
ALTER TABLE rate_limit_configs          ENABLE ROW LEVEL SECURITY;
ALTER TABLE rate_limit_counters         ENABLE ROW LEVEL SECURITY;
ALTER TABLE intent_events               ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_log                   ENABLE ROW LEVEL SECURITY;

-- FORCE RLS
ALTER TABLE tenants                     FORCE ROW LEVEL SECURITY;
ALTER TABLE organizations               FORCE ROW LEVEL SECURITY;
ALTER TABLE user_organizations          FORCE ROW LEVEL SECURITY;
ALTER TABLE api_keys                    FORCE ROW LEVEL SECURITY;
ALTER TABLE adapters                    FORCE ROW LEVEL SECURITY;
ALTER TABLE intents                     FORCE ROW LEVEL SECURITY;
ALTER TABLE intent_plans                FORCE ROW LEVEL SECURITY;
ALTER TABLE intent_plan_steps           FORCE ROW LEVEL SECURITY;
ALTER TABLE execution_records           FORCE ROW LEVEL SECURITY;
ALTER TABLE spend_records               FORCE ROW LEVEL SECURITY;
ALTER TABLE sla_windows                 FORCE ROW LEVEL SECURITY;
ALTER TABLE intent_drift_evaluations    FORCE ROW LEVEL SECURITY;
ALTER TABLE policies                    FORCE ROW LEVEL SECURITY;
ALTER TABLE policy_evaluations          FORCE ROW LEVEL SECURITY;
ALTER TABLE adapter_performance_profiles FORCE ROW LEVEL SECURITY;
ALTER TABLE adapter_profile_versions    FORCE ROW LEVEL SECURITY;
ALTER TABLE rate_limit_configs          FORCE ROW LEVEL SECURITY;
ALTER TABLE rate_limit_counters         FORCE ROW LEVEL SECURITY;
ALTER TABLE intent_events               FORCE ROW LEVEL SECURITY;
ALTER TABLE audit_log                   FORCE ROW LEVEL SECURITY;

-- ── RLS Policies ─────────────────────────────────────────
CREATE POLICY rls_tenants_isolation ON tenants AS PERMISSIVE FOR ALL TO app_role USING (id = fn_current_tenant_id());

CREATE POLICY rls_organizations_isolation ON organizations AS PERMISSIVE FOR ALL TO app_role USING (tenant_id = fn_current_tenant_id());

CREATE POLICY rls_user_orgs_isolation ON user_organizations AS PERMISSIVE FOR ALL TO app_role USING (tenant_id = fn_current_tenant_id());

CREATE POLICY rls_api_keys_isolation ON api_keys AS PERMISSIVE FOR ALL TO app_role USING (tenant_id = fn_current_tenant_id());

CREATE POLICY rls_adapters_isolation ON adapters AS PERMISSIVE FOR ALL TO app_role USING (tenant_id = fn_current_tenant_id());
CREATE POLICY rls_intents_isolation ON intents AS PERMISSIVE FOR ALL TO app_role USING (tenant_id = fn_current_tenant_id());
CREATE POLICY rls_intent_plans_isolation ON intent_plans AS PERMISSIVE FOR ALL TO app_role USING (tenant_id = fn_current_tenant_id());
CREATE POLICY rls_intent_plan_steps_isolation ON intent_plan_steps AS PERMISSIVE FOR ALL TO app_role USING (tenant_id = fn_current_tenant_id());
CREATE POLICY rls_execution_records_isolation ON execution_records AS PERMISSIVE FOR ALL TO app_role USING (tenant_id = fn_current_tenant_id());
CREATE POLICY rls_spend_records_isolation ON spend_records AS PERMISSIVE FOR ALL TO app_role USING (tenant_id = fn_current_tenant_id());
CREATE POLICY rls_sla_windows_isolation ON sla_windows AS PERMISSIVE FOR ALL TO app_role USING (tenant_id = fn_current_tenant_id());
CREATE POLICY rls_drift_eval_isolation ON intent_drift_evaluations AS PERMISSIVE FOR ALL TO app_role USING (tenant_id = fn_current_tenant_id());
CREATE POLICY rls_policies_isolation ON policies AS PERMISSIVE FOR ALL TO app_role USING (tenant_id = fn_current_tenant_id());
CREATE POLICY rls_policy_eval_isolation ON policy_evaluations AS PERMISSIVE FOR ALL TO app_role USING (tenant_id = fn_current_tenant_id());
CREATE POLICY rls_profiles_isolation ON adapter_performance_profiles AS PERMISSIVE FOR ALL TO app_role USING (tenant_id = fn_current_tenant_id());
CREATE POLICY rls_profile_versions_isolation ON adapter_profile_versions AS PERMISSIVE FOR ALL TO app_role USING (tenant_id = fn_current_tenant_id());
CREATE POLICY rls_rl_config_isolation ON rate_limit_configs AS PERMISSIVE FOR ALL TO app_role USING (tenant_id = fn_current_tenant_id());
CREATE POLICY rls_rl_counter_isolation ON rate_limit_counters AS PERMISSIVE FOR ALL TO app_role USING (tenant_id = fn_current_tenant_id());
CREATE POLICY rls_events_isolation ON intent_events AS PERMISSIVE FOR ALL TO app_role USING (tenant_id = fn_current_tenant_id());
CREATE POLICY rls_audit_isolation ON audit_log AS PERMISSIVE FOR ALL TO app_role USING (tenant_id = fn_current_tenant_id());

-- ============================================================
-- V9: DOMAIN FUNCTIONS AND TRIGGERS
-- ============================================================

-- Idempotency check
CREATE OR REPLACE FUNCTION fn_find_by_idempotency_key(p_tenant_id UUID, p_idempotency_key VARCHAR(255))
RETURNS TABLE (id UUID, phase VARCHAR(50), satisfaction_state VARCHAR(50), version INT, created_at TIMESTAMPTZ)
LANGUAGE sql STABLE AS $$
SELECT id, phase, satisfaction_state, version, created_at
FROM   intents
WHERE  tenant_id = p_tenant_id AND idempotency_key = p_idempotency_key
    LIMIT  1;
$$;

-- Cumulative spend
CREATE OR REPLACE FUNCTION fn_intent_cumulative_spend(p_tenant_id UUID, p_intent_id UUID)
RETURNS NUMERIC(12,6) LANGUAGE sql STABLE AS $$
SELECT COALESCE(SUM(amount_usd), 0)
FROM   spend_records
WHERE  tenant_id = p_tenant_id AND intent_id = p_intent_id;
$$;

-- Budget check
CREATE OR REPLACE FUNCTION fn_would_exceed_budget(p_tenant_id UUID, p_intent_id UUID, p_proposed_cost NUMERIC(12,6))
RETURNS BOOLEAN LANGUAGE sql STABLE AS $$
SELECT CASE
           WHEN i.budget_ceiling_usd IS NULL THEN FALSE
           ELSE (fn_intent_cumulative_spend(p_tenant_id, p_intent_id) + p_proposed_cost) > i.budget_ceiling_usd
           END
FROM  intents i
WHERE i.tenant_id = p_tenant_id AND i.id = p_intent_id;
$$;

-- SLA elapsed
CREATE OR REPLACE FUNCTION fn_intent_elapsed_ms(p_tenant_id UUID, p_intent_id UUID)
RETURNS BIGINT LANGUAGE sql STABLE AS $$
SELECT EXTRACT(EPOCH FROM (now() - created_at))::BIGINT * 1000
FROM   intents
WHERE  tenant_id = p_tenant_id AND id = p_intent_id;
$$;

-- SLA breach check
CREATE OR REPLACE FUNCTION fn_is_sla_breached(p_tenant_id UUID, p_intent_id UUID)
RETURNS BOOLEAN LANGUAGE sql STABLE AS $$
SELECT CASE
           WHEN i.sla_deadline_ms IS NULL THEN FALSE
           ELSE fn_intent_elapsed_ms(p_tenant_id, p_intent_id) > i.sla_deadline_ms
           END
FROM  intents i
WHERE i.tenant_id = p_tenant_id AND i.id = p_intent_id;
$$;

-- Phase transition guard
CREATE OR REPLACE FUNCTION fn_guard_phase_transition()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
allowed_transitions JSONB := '{
        "SUBMITTED":       ["PLANNING","CANCELLED"],
        "PLANNING":        ["EXECUTING","CANCELLED","VIOLATED"],
        "EXECUTING":       ["EVALUATING","RETRY_SCHEDULED","CANCELLED","VIOLATED"],
        "EVALUATING":      ["SATISFIED","VIOLATED","RETRY_SCHEDULED"],
        "RETRY_SCHEDULED": ["PLANNING","CANCELLED"],
        "SATISFIED":       [], "VIOLATED":        [], "CANCELLED":       []
    }'::JSONB;
    valid_next TEXT[];
BEGIN
    IF OLD.phase = NEW.phase THEN RETURN NEW; END IF;
SELECT ARRAY(SELECT jsonb_array_elements_text(allowed_transitions -> OLD.phase)) INTO valid_next;
IF NOT (NEW.phase = ANY(valid_next)) THEN
        RAISE EXCEPTION 'Invalid phase transition: % → % (intent_id=%)', OLD.phase, NEW.phase, OLD.id;
END IF;
RETURN NEW;
END;
$$;

CREATE TRIGGER trg_intent_phase_guard BEFORE UPDATE OF phase ON intents
    FOR EACH ROW EXECUTE FUNCTION fn_guard_phase_transition();

-- Plan version auto-increment
CREATE OR REPLACE FUNCTION fn_auto_plan_version()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
SELECT COALESCE(MAX(plan_version), 0) + 1 INTO NEW.plan_version
FROM   intent_plans WHERE intent_id = NEW.intent_id;
RETURN NEW;
END;
$$;

CREATE TRIGGER trg_intent_plans_auto_version BEFORE INSERT ON intent_plans
    FOR EACH ROW EXECUTE FUNCTION fn_auto_plan_version();

-- Attempt number auto-increment
CREATE OR REPLACE FUNCTION fn_auto_attempt_number()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
SELECT COALESCE(MAX(attempt_number), 0) + 1 INTO NEW.attempt_number
FROM   execution_records WHERE intent_id = NEW.intent_id;
RETURN NEW;
END;
$$;

CREATE TRIGGER trg_execution_auto_attempt BEFORE INSERT ON execution_records
    FOR EACH ROW EXECUTE FUNCTION fn_auto_attempt_number();

-- Cumulative spend population
CREATE OR REPLACE FUNCTION fn_populate_cumulative_spend()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
SELECT COALESCE(SUM(amount_usd), 0) + NEW.amount_usd INTO NEW.cumulative_spend_usd
FROM   spend_records
WHERE  tenant_id = NEW.tenant_id AND intent_id = NEW.intent_id;
RETURN NEW;
END;
$$;

CREATE TRIGGER trg_spend_cumulative BEFORE INSERT ON spend_records
    FOR EACH ROW EXECUTE FUNCTION fn_populate_cumulative_spend();

-- Retry count guard
CREATE OR REPLACE FUNCTION fn_guard_retry_count()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.retry_count > NEW.max_retries THEN
        RAISE EXCEPTION 'retry_count (%) exceeds max_retries (%) for intent_id=%',
            NEW.retry_count, NEW.max_retries, NEW.id;
END IF;
RETURN NEW;
END;
$$;

CREATE TRIGGER trg_intent_retry_guard BEFORE UPDATE OF retry_count ON intents
    FOR EACH ROW EXECUTE FUNCTION fn_guard_retry_count();

-- ============================================================
-- V10: ANALYTICS VIEWS AND PARTITIONING
-- ============================================================

-- Additional indexes for query patterns
CREATE INDEX idx_intents_reconcile ON intents (tenant_id, phase, updated_at ASC)
    WHERE phase IN ('PLANNING','EXECUTING','EVALUATING','RETRY_SCHEDULED');
CREATE INDEX idx_adapters_planner_lookup ON adapters (tenant_id, adapter_type, is_active)
    INCLUDE (name, provider, region, base_cost_per_token, max_tokens_per_call);
CREATE INDEX idx_profiles_planner_rank ON adapter_performance_profiles
    (tenant_id, composite_score DESC, is_degraded, cold_start)
    INCLUDE (adapter_id, ema_cost, ema_latency_ms, ema_success_rate);
CREATE INDEX idx_policies_eval_lookup ON policies (tenant_id, phase, priority ASC, is_active)
    INCLUDE (id, policy_type, enforcement_mode, rule_dsl) WHERE is_active = TRUE;
CREATE INDEX idx_spend_latest_per_intent ON spend_records (intent_id, recorded_at DESC)
    INCLUDE (cumulative_spend_usd, remaining_budget_usd);
CREATE INDEX idx_exec_learning_feed ON execution_records (tenant_id, adapter_id, executed_at DESC)
    INCLUDE (status, cost_usd, latency_ms, risk_score, total_tokens);
CREATE INDEX idx_rl_window_lookup ON rate_limit_counters (scope_key, window_start DESC, window_end)
    INCLUDE (count, was_blocked);

-- Analytics views
CREATE VIEW v_intent_status_summary AS
SELECT tenant_id, intent_type, phase, satisfaction_state, COUNT(*) AS intent_count,
       AVG(drift_score) AS avg_drift_score,
       PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY drift_score) AS median_drift_score,
       MIN(created_at) AS oldest_intent, MAX(created_at) AS newest_intent
FROM   intents
GROUP  BY tenant_id, intent_type, phase, satisfaction_state;

CREATE VIEW v_adapter_execution_summary AS
SELECT er.tenant_id, er.adapter_id, a.name AS adapter_name, a.provider,
       COUNT(*) AS total_attempts,
       COUNT(*) FILTER (WHERE er.status = 'SUCCESS') AS success_count,
    COUNT(*) FILTER (WHERE er.status != 'SUCCESS') AS failure_count,
    ROUND(COUNT(*) FILTER (WHERE er.status = 'SUCCESS')::NUMERIC / NULLIF(COUNT(*), 0) * 100, 2) AS success_rate_pct,
       AVG(er.latency_ms) AS avg_latency_ms,
       PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY er.latency_ms) AS p95_latency_ms,
       SUM(er.cost_usd) AS total_cost_usd, AVG(er.cost_usd) AS avg_cost_usd,
       AVG(er.risk_score) AS avg_risk_score, MAX(er.executed_at) AS last_executed_at
FROM   execution_records er JOIN adapters a ON a.id = er.adapter_id
GROUP  BY er.tenant_id, er.adapter_id, a.name, a.provider;

CREATE VIEW v_policy_violation_summary AS
SELECT pe.tenant_id, pe.phase, p.policy_type, p.enforcement_mode,
       COUNT(*) AS evaluation_count,
       COUNT(*) FILTER (WHERE pe.result = 'VIOLATION') AS violation_count,
    COUNT(*) FILTER (WHERE pe.result = 'WARNING') AS warning_count,
    ROUND(COUNT(*) FILTER (WHERE pe.result = 'VIOLATION')::NUMERIC / NULLIF(COUNT(*), 0) * 100, 2) AS violation_rate_pct,
       MAX(pe.evaluated_at) AS last_evaluated_at
FROM   policy_evaluations pe JOIN policies p ON p.id = pe.policy_id
GROUP  BY pe.tenant_id, pe.phase, p.policy_type, p.enforcement_mode;

CREATE VIEW v_intent_budget_utilisation AS
SELECT i.tenant_id, i.id AS intent_id, i.intent_type, i.phase, i.budget_ceiling_usd,
       fn_intent_cumulative_spend(i.tenant_id, i.id) AS cumulative_spend_usd,
       CASE WHEN i.budget_ceiling_usd IS NULL THEN NULL
            ELSE ROUND(fn_intent_cumulative_spend(i.tenant_id, i.id) / NULLIF(i.budget_ceiling_usd, 0) * 100, 2)
           END AS budget_utilisation_pct,
       i.created_at
FROM   intents i WHERE i.phase NOT IN ('CANCELLED');

CREATE VIEW v_sla_compliance_summary AS
SELECT sw.tenant_id, i.intent_type, COUNT(DISTINCT sw.intent_id) AS total_intents,
       COUNT(DISTINCT sw.intent_id) FILTER (WHERE sw.breached = TRUE) AS breached_count,
    ROUND(COUNT(DISTINCT sw.intent_id) FILTER (WHERE sw.breached = TRUE)::NUMERIC / NULLIF(COUNT(DISTINCT sw.intent_id), 0) * 100, 2) AS breach_rate_pct,
       AVG(sw.elapsed_ms) AS avg_elapsed_ms,
       PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY sw.elapsed_ms) AS p95_elapsed_ms
FROM   sla_windows sw JOIN intents i ON i.id = sw.intent_id
WHERE  sw.snapshotted_at = (SELECT MAX(s2.snapshotted_at) FROM sla_windows s2 WHERE s2.intent_id = sw.intent_id)
GROUP  BY sw.tenant_id, i.intent_type;

-- Global sequence for event ordering
CREATE SEQUENCE seq_global_event_order START 1 INCREMENT 1 NO CYCLE;
ALTER TABLE intent_events ADD COLUMN global_seq BIGINT NOT NULL DEFAULT nextval('seq_global_event_order');
CREATE INDEX idx_events_global_seq ON intent_events (global_seq);

-- ============================================================
-- SCHEMA DEPLOYMENT COMPLETE
-- ============================================================
-- CRITICAL FIXES APPLIED:
-- ✓ V8: Fixed CURRENT_DATABASE → current_database() with format()
-- ✓ V8: Added GRANT USAGE, SELECT ON ALL SEQUENCES
-- ✓ V8: Removed hardcoded password from migration
-- ✓ V6: Changed chk_window_order from > to >=
--
-- SCHEMA STATISTICS:
-- Total Tables:     21 (tenants, organizations, users, user_organizations,
--                       api_keys, adapters, intents, intent_plans,
--                       intent_plan_steps, execution_records, spend_records,
--                       sla_windows, intent_drift_evaluations, policies,
--                       policy_evaluations, adapter_performance_profiles,
--                       adapter_profile_versions, rate_limit_configs,
--                       rate_limit_counters, intent_events, audit_log)
-- Total Indexes:    120+
-- Total Functions:  13
-- Total Triggers:   18
-- Total Views:      5
-- Total Sequences:  2
-- RLS Policies:     20 (all tenant-scoped tables)
--
-- DEPLOYMENT NOTES:
-- 1. Create app_user separately via secure deployment script:
--    CREATE ROLE app_user LOGIN PASSWORD '${SECURE_PASSWORD}' IN ROLE app_role;
--
-- 2. Configure Quarkus application.properties:
--    quarkus.flyway.migrate-at-start=true
--    quarkus.hibernate-orm.database.generation=none
--
-- 3. Implement TenantContextInterceptor to set app.current_tenant_id
--
-- 4. Plan partitioning when tables exceed 10M rows:
--    - execution_records (by executed_at)
--    - intent_events (by occurred_at)
--    - audit_log (by occurred_at)
--
-- PRODUCTION READINESS:
-- Schema is enterprise-grade with multi-tenant RLS, event sourcing,
-- adaptive learning, comprehensive observability, and domain invariants
-- enforced at database level.
-- ============================================================