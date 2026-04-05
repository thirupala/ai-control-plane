"DefaultJWTCallerPrincipal{id='f38fb57b-8b60-48e2-84bb-007e8d3bd83d', name='thirupala@gmail.com', expiration=1775356264, notBefore=0, issuedAt=1775355964, issuer='http://localhost:8180/realms/decisionmesh', audience=[control-plane-backend, account], subject='673ebe93-def1-4eb4-9bed-04fe0bb99dca', type='JWT', issuedFor='control-plane-web', authTime=1775355964, givenName='annam', familyName='sharonrose', middleName='null', nickName='null', preferredUsername='thirupala@gmail.com', email='thirupala@gmail.com', emailVerified=false, allowedOrigins=null, updatedAt=0, acr='1', groups=[]}"



CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ============================================================
-- UTILITY FUNCTIONS
-- ============================================================

CREATE OR REPLACE FUNCTION fn_set_updated_at()
    RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    NEW.updated_at = now();
RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION fn_guard_immutable()
    RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Immutable record violation: table=% id=%', TG_TABLE_NAME, OLD.id;
RETURN NULL;
END;
$$;

CREATE OR REPLACE FUNCTION fn_current_audit_user()
    RETURNS VARCHAR(255) LANGUAGE plpgsql AS $$
BEGIN
RETURN current_setting('app.current_user_id', TRUE);
END;
$$;

-- ============================================================
-- CORE IDENTITY & TENANCY
-- ============================================================

CREATE TABLE tenants (
                         id              UUID PRIMARY KEY      DEFAULT gen_random_uuid(),
                         external_id     VARCHAR(255) NOT NULL UNIQUE,
                         name            VARCHAR(255) NOT NULL,
                         status          VARCHAR(50)  NOT NULL DEFAULT 'ACTIVE',
                         config          JSONB                 DEFAULT '{}',
                         created_at      TIMESTAMPTZ           DEFAULT now(),
                         updated_at      TIMESTAMPTZ           DEFAULT now()
);

CREATE TABLE organizations (
                               id          UUID PRIMARY KEY      DEFAULT gen_random_uuid(),
                               tenant_id   UUID         NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
                               name        VARCHAR(255) NOT NULL,
                               description TEXT,
                               plan        VARCHAR(20)  NOT NULL DEFAULT 'FREE',
                               config      JSONB                 DEFAULT '{}',
                               is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
                               created_at  TIMESTAMPTZ           DEFAULT now(),
                               updated_at  TIMESTAMPTZ           DEFAULT now()
);

CREATE TABLE users (
                       id               UUID PRIMARY KEY     DEFAULT gen_random_uuid(),
                       external_user_id UUID UNIQUE, -- Link to Keycloak 'sub'
                       email            VARCHAR(255) UNIQUE,
                       name             VARCHAR(255),
                       is_active        BOOLEAN     NOT NULL DEFAULT TRUE,
                       created_at       TIMESTAMPTZ          DEFAULT now(),
                       updated_at       TIMESTAMPTZ          DEFAULT now()
);

CREATE TABLE user_organizations (
                                    id              UUID PRIMARY KEY     DEFAULT gen_random_uuid(),
                                    user_id         UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
                                    organization_id UUID        NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
                                    tenant_id       UUID        NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
                                    role            VARCHAR(20) NOT NULL,
                                    permissions     JSONB       NOT NULL DEFAULT '[]',
                                    is_active       BOOLEAN     NOT NULL DEFAULT TRUE,
                                    created_at      TIMESTAMPTZ          DEFAULT now(),
                                    updated_at      TIMESTAMPTZ          DEFAULT now(),
                                    CONSTRAINT chk_role CHECK (role IN ('tenant_admin', 'tenant_analyst', 'tenant_viewer')),
                                    CONSTRAINT uq_user_org UNIQUE (user_id, organization_id)
);

-- ============================================================
-- PROJECTS & MEMBERSHIP
-- ============================================================

CREATE TABLE projects (
                          id          UUID PRIMARY KEY      DEFAULT gen_random_uuid(),
                          tenant_id   UUID         NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
                          name        VARCHAR(255) NOT NULL,
                          description TEXT,
                          environment VARCHAR(50)  NOT NULL DEFAULT 'Production',
                          is_default  BOOLEAN      NOT NULL DEFAULT FALSE,
                          created_at  TIMESTAMPTZ           DEFAULT now(),
                          CONSTRAINT chk_env CHECK (environment IN ('Development', 'Staging', 'Production', 'Sandbox'))
);

CREATE TABLE members (
                         id             UUID PRIMARY KEY     DEFAULT gen_random_uuid(),
                         tenant_id      UUID        NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
                         user_id        UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
                         project_id     UUID                 REFERENCES projects (id) ON DELETE CASCADE,
                         role           VARCHAR(20) NOT NULL DEFAULT 'VIEWER',
                         joined_at      TIMESTAMPTZ          DEFAULT now(),
                         last_active_at TIMESTAMPTZ,
                         CONSTRAINT chk_member_role CHECK (role IN ('ADMIN', 'ANALYST', 'VIEWER')),
                         CONSTRAINT uq_tenant_user_project UNIQUE NULLS NOT DISTINCT (tenant_id, user_id, project_id)
);

-- ============================================================
-- AI CORE (ADAPTERS, INTENTS, PLANS)
-- ============================================================

CREATE TABLE adapters (
                          id                   UUID PRIMARY KEY      DEFAULT gen_random_uuid(),
                          tenant_id            UUID         NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
                          name                 VARCHAR(255) NOT NULL,
                          adapter_type         VARCHAR(100) CHECK (adapter_type IN ('LLM', 'EMBEDDING', 'TOOL', 'RETRIEVAL', 'CUSTOM')),
                          provider             VARCHAR(100),
                          model_id             VARCHAR(255),
                          config               JSONB        NOT NULL DEFAULT '{}',
                          is_active            BOOLEAN      NOT NULL DEFAULT TRUE,
                          created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
                          updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE intents (
                         id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                         tenant_id          UUID         NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
                         user_id            UUID         REFERENCES users (id),
                         intent_type        VARCHAR(255) NOT NULL,
                         phase              VARCHAR(50)  NOT NULL,
                         satisfaction_state VARCHAR(50)  NOT NULL DEFAULT 'UNKNOWN',
                         terminal           BOOLEAN      NOT NULL DEFAULT FALSE,
                         payload            JSONB        NOT NULL,
                         created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
                         updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE intent_plans (
                              id                UUID PRIMARY KEY     DEFAULT gen_random_uuid(),
                              intent_id         UUID NOT NULL REFERENCES intents (id) ON DELETE CASCADE,
                              tenant_id         UUID NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
                              strategy          VARCHAR(50) NOT NULL DEFAULT 'SINGLE_ADAPTER',
                              status            VARCHAR(50) NOT NULL DEFAULT 'PENDING',
                              created_at        TIMESTAMPTZ          DEFAULT now()
);

CREATE TABLE execution_records (
                                   id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                   intent_id              UUID NOT NULL REFERENCES intents (id) ON DELETE CASCADE,
                                   tenant_id              UUID NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
                                   adapter_id             UUID REFERENCES adapters (id),
                                   plan_id                UUID REFERENCES intent_plans (id) ON DELETE CASCADE,
                                   status                 VARCHAR(50),
                                   cost_usd               NUMERIC(12, 6),
                                   latency_ms             BIGINT,
                                   response_text          TEXT,
                                   metadata               JSONB            DEFAULT '{}',
                                   executed_at            TIMESTAMPTZ      DEFAULT now()
);

-- ============================================================
-- AUDIT & GOVERNANCE
-- ============================================================

CREATE TABLE audit_log (
                           id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                           tenant_id     UUID,
                           user_id       VARCHAR(255),
                           entity_type   VARCHAR(100),
                           entity_id     UUID,
                           action        VARCHAR(100),
                           detail        TEXT,
                           occurred_at   TIMESTAMPTZ      DEFAULT now()
);

CREATE TABLE ledger_entry (
                              id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                              intent_id          UUID REFERENCES intents (id) ON DELETE CASCADE,
                              tenant_id          UUID REFERENCES tenants (id) ON DELETE CASCADE,
                              aggregate_version  BIGINT NOT NULL DEFAULT 0,
                              policy_snapshot    JSONB, -- Changed from OID
                              budget_snapshot    JSONB, -- Changed from OID
                              timestamp          TIMESTAMPTZ DEFAULT now()
);

-- ============================================================
-- TRIGGERS (AUDIT & UPDATED_AT)
-- ============================================================

-- Audit Function for Intents
CREATE OR REPLACE FUNCTION fn_audit_intents() RETURNS TRIGGER AS $$
DECLARE
v_action VARCHAR(100);
    v_uid    VARCHAR(255) := COALESCE(fn_current_audit_user(), 'SYSTEM');
BEGIN
    IF TG_OP = 'INSERT' THEN v_action := 'INTENT_SUBMITTED';
    ELSIF TG_OP = 'UPDATE' THEN
        IF NEW.terminal AND NOT OLD.terminal THEN v_action := 'INTENT_COMPLETED';
ELSE v_action := 'INTENT_UPDATED'; END IF;
    ELSIF TG_OP = 'DELETE' THEN
        INSERT INTO audit_log (tenant_id, user_id, entity_type, entity_id, action, detail)
        VALUES (OLD.tenant_id, v_uid, 'INTENT', OLD.id, 'INTENT_DELETED', OLD.intent_type);
RETURN OLD;
END IF;
INSERT INTO audit_log (tenant_id, user_id, entity_type, entity_id, action, detail)
VALUES (NEW.tenant_id, v_uid, 'INTENT', NEW.id, v_action, NEW.intent_type);
RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_intents AFTER INSERT OR UPDATE OR DELETE ON intents FOR EACH ROW EXECUTE FUNCTION fn_audit_intents();

-- Global Updated_at Triggers
CREATE TRIGGER trg_tenants_upd BEFORE UPDATE ON tenants FOR EACH ROW EXECUTE FUNCTION fn_set_updated_at();
CREATE TRIGGER trg_orgs_upd BEFORE UPDATE ON organizations FOR EACH ROW EXECUTE FUNCTION fn_set_updated_at();
CREATE TRIGGER trg_users_upd BEFORE UPDATE ON users FOR EACH ROW EXECUTE FUNCTION fn_set_updated_at();
CREATE TRIGGER trg_adapters_upd BEFORE UPDATE ON adapters FOR EACH ROW EXECUTE FUNCTION fn_set_updated_at();