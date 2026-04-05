-- ─────────────────────────────────────────────────────────────────────────────
-- V2__audit_log_triggers.sql
--
-- PostgreSQL triggers that write to audit_log automatically whenever
-- intents, api_keys, policies, or adapters are inserted, updated, or deleted.
--
-- Why triggers instead of Java:
--   • Atomic with the write — audit row is in the same transaction
--   • No Hibernate Reactive session/context issues
--   • Works for any writer (Java, admin queries, migrations)
--
-- User identity:
--   The application sets current_setting('app.current_user_id') on each
--   connection before performing writes. Falls back to NULL gracefully if
--   not set (e.g. background jobs).
--   To set it from Java: use a Vert.x connection hook or SET LOCAL in the
--   same transaction:
--     SET LOCAL "app.current_user_id" = '<user-uuid>';
-- ─────────────────────────────────────────────────────────────────────────────

-- ── Helper: read current user from session config ─────────────────────────────

CREATE OR REPLACE FUNCTION fn_current_audit_user()
    RETURNS VARCHAR(255) LANGUAGE plpgsql AS
$$
BEGIN
RETURN current_setting('app.current_user_id', TRUE);  -- TRUE = missing_ok
EXCEPTION
    WHEN OTHERS THEN RETURN NULL;
END;
$$;

-- ═══════════════════════════════════════════════════════════════════════════════
-- INTENTS
--   INSERT  → INTENT_SUBMITTED
--   UPDATE  → INTENT_PHASE_CHANGED (when phase changes)
--             INTENT_SATISFIED     (when terminal=TRUE, satisfaction_state=SATISFIED)
--             INTENT_VIOLATED      (when terminal=TRUE, satisfaction_state=VIOLATED)
--             INTENT_DELETED is handled by DELETE below
--   DELETE  → INTENT_DELETED
-- ═══════════════════════════════════════════════════════════════════════════════

CREATE OR REPLACE FUNCTION fn_audit_intents()
    RETURNS TRIGGER LANGUAGE plpgsql AS
$$
DECLARE
v_action VARCHAR(100);
    v_detail TEXT;
    v_uid    VARCHAR(255);
BEGIN
    v_uid := COALESCE(fn_current_audit_user(), NEW.user_id::TEXT);

    IF TG_OP = 'INSERT' THEN
        v_action := 'INTENT_SUBMITTED';
        v_detail := 'type=' || NEW.intent_type || ' phase=' || NEW.phase;

    ELSIF TG_OP = 'UPDATE' THEN
        IF NEW.terminal = TRUE AND OLD.terminal = FALSE THEN
            v_action := CASE NEW.satisfaction_state
                            WHEN 'SATISFIED' THEN 'INTENT_SATISFIED'
                            WHEN 'VIOLATED'  THEN 'INTENT_VIOLATED'
                            ELSE                  'INTENT_COMPLETED'
END;
            v_detail := 'phase=' || NEW.phase
                || ' satisfaction=' || NEW.satisfaction_state
                || ' retries='      || NEW.retry_count;
        ELSIF NEW.phase IS DISTINCT FROM OLD.phase THEN
            v_action := 'INTENT_PHASE_CHANGED';
            v_detail := 'from=' || COALESCE(OLD.phase, '—')
                || ' to='   || NEW.phase;
ELSE
            RETURN NEW;  -- uninteresting update — skip audit row
END IF;

    ELSIF TG_OP = 'DELETE' THEN
        v_uid    := COALESCE(fn_current_audit_user(), OLD.user_id::TEXT);
        v_action := 'INTENT_DELETED';
        v_detail := 'type=' || OLD.intent_type || ' phase=' || OLD.phase;

INSERT INTO audit_log (tenant_id, user_id, entity_type, entity_id, action, detail)
VALUES (OLD.tenant_id, v_uid, 'INTENT', OLD.id, v_action, v_detail);
RETURN OLD;
END IF;

INSERT INTO audit_log (tenant_id, user_id, entity_type, entity_id, action, detail)
VALUES (NEW.tenant_id, v_uid, 'INTENT', NEW.id, v_action, v_detail);

RETURN NEW;
END;
$$;

CREATE TRIGGER trg_audit_intents
    AFTER INSERT OR UPDATE OR DELETE ON intents
    FOR EACH ROW EXECUTE FUNCTION fn_audit_intents();

-- ═══════════════════════════════════════════════════════════════════════════════
-- API KEYS
--   INSERT → API_KEY_CREATED
--   UPDATE → API_KEY_REVOKED  (when revoked_at transitions from NULL to non-NULL)
--   DELETE → API_KEY_DELETED
-- ═══════════════════════════════════════════════════════════════════════════════

CREATE OR REPLACE FUNCTION fn_audit_api_keys()
    RETURNS TRIGGER LANGUAGE plpgsql AS
$$
DECLARE
v_action VARCHAR(100);
    v_detail TEXT;
    v_uid    VARCHAR(255);
BEGIN
    -- created_by_user_id is the user who owns/created the key
    IF TG_OP = 'DELETE' THEN
        v_uid    := COALESCE(fn_current_audit_user(), OLD.created_by_user_id::TEXT);
        v_action := 'API_KEY_DELETED';
        v_detail := 'prefix=' || OLD.key_prefix || ' name=' || COALESCE(OLD.name, '—');

INSERT INTO audit_log (tenant_id, user_id, entity_type, entity_id, action, detail)
VALUES (OLD.tenant_id, v_uid, 'API_KEY', OLD.key_id, v_action, v_detail);
RETURN OLD;
END IF;

    v_uid := COALESCE(fn_current_audit_user(), NEW.created_by_user_id::TEXT);

    IF TG_OP = 'INSERT' THEN
        v_action := 'API_KEY_CREATED';
        v_detail := 'prefix=' || NEW.key_prefix
            || ' name='   || COALESCE(NEW.name, '—')
            || ' scopes='  || COALESCE(NEW.scopes::TEXT, '[]');

    ELSIF TG_OP = 'UPDATE' THEN
        IF NEW.revoked_at IS NOT NULL AND OLD.revoked_at IS NULL THEN
            v_action := 'API_KEY_REVOKED';
            v_detail := 'prefix=' || NEW.key_prefix
                || ' name=' || COALESCE(NEW.name, '—');
ELSE
            RETURN NEW;  -- usage_count increment, last_used_at update — skip
END IF;
END IF;

INSERT INTO audit_log (tenant_id, user_id, entity_type, entity_id, action, detail)
VALUES (NEW.tenant_id, v_uid, 'API_KEY', NEW.key_id, v_action, v_detail);

RETURN NEW;
END;
$$;

CREATE TRIGGER trg_audit_api_keys
    AFTER INSERT OR UPDATE OR DELETE ON api_keys
    FOR EACH ROW EXECUTE FUNCTION fn_audit_api_keys();

-- ═══════════════════════════════════════════════════════════════════════════════
-- POLICIES
--   INSERT → POLICY_CREATED
--   UPDATE → POLICY_UPDATED
--   DELETE → POLICY_DELETED
-- ═══════════════════════════════════════════════════════════════════════════════

CREATE OR REPLACE FUNCTION fn_audit_policies()
    RETURNS TRIGGER LANGUAGE plpgsql AS
$$
DECLARE
v_action VARCHAR(100);
    v_detail TEXT;
    v_uid    VARCHAR(255);
BEGIN
    v_uid := fn_current_audit_user();  -- policies have no user_id column

    IF TG_OP = 'DELETE' THEN
        v_action := 'POLICY_DELETED';
        v_detail := 'name=' || COALESCE(OLD.name, '—')
            || ' phase=' || OLD.phase;

INSERT INTO audit_log (tenant_id, user_id, entity_type, entity_id, action, detail)
VALUES (OLD.tenant_id, v_uid, 'POLICY', OLD.id, v_action, v_detail);
RETURN OLD;
END IF;

    v_action := CASE TG_OP
                    WHEN 'INSERT' THEN 'POLICY_CREATED'
                    ELSE               'POLICY_UPDATED'
END;
    v_detail := 'name='  || COALESCE(NEW.name, '—')
        || ' phase='     || NEW.phase
        || ' enforcement=' || NEW.enforcement_mode;

INSERT INTO audit_log (tenant_id, user_id, entity_type, entity_id, action, detail)
VALUES (NEW.tenant_id, v_uid, 'POLICY', NEW.id, v_action, v_detail);

RETURN NEW;
END;
$$;

CREATE TRIGGER trg_audit_policies
    AFTER INSERT OR UPDATE OR DELETE ON policies
    FOR EACH ROW EXECUTE FUNCTION fn_audit_policies();

-- ═══════════════════════════════════════════════════════════════════════════════
-- ADAPTERS
--   INSERT → ADAPTER_CREATED
--   UPDATE → ADAPTER_UPDATED  (name/config changed)
--             ADAPTER_ENABLED / ADAPTER_DISABLED  (is_active toggled)
--   DELETE → ADAPTER_DELETED
-- ═══════════════════════════════════════════════════════════════════════════════

CREATE OR REPLACE FUNCTION fn_audit_adapters()
    RETURNS TRIGGER LANGUAGE plpgsql AS
$$
DECLARE
v_action VARCHAR(100);
    v_detail TEXT;
    v_uid    VARCHAR(255);
BEGIN
    v_uid := fn_current_audit_user();  -- adapters have no user_id column

    IF TG_OP = 'DELETE' THEN
        v_action := 'ADAPTER_DELETED';
        v_detail := 'name=' || OLD.name || ' provider=' || COALESCE(OLD.provider, '—');

INSERT INTO audit_log (tenant_id, user_id, entity_type, entity_id, action, detail)
VALUES (OLD.tenant_id, v_uid, 'ADAPTER', OLD.id, v_action, v_detail);
RETURN OLD;
END IF;

    IF TG_OP = 'UPDATE' AND NEW.is_active IS DISTINCT FROM OLD.is_active THEN
        v_action := CASE WHEN NEW.is_active THEN 'ADAPTER_ENABLED' ELSE 'ADAPTER_DISABLED' END;
    ELSIF TG_OP = 'INSERT' THEN
        v_action := 'ADAPTER_CREATED';
ELSE
        v_action := 'ADAPTER_UPDATED';
END IF;

    v_detail := 'name='     || NEW.name
        || ' provider='     || COALESCE(NEW.provider, '—')
        || ' model='        || COALESCE(NEW.model_id, '—')
        || ' active='       || NEW.is_active;

INSERT INTO audit_log (tenant_id, user_id, entity_type, entity_id, action, detail)
VALUES (NEW.tenant_id, v_uid, 'ADAPTER', NEW.id, v_action, v_detail);

RETURN NEW;
END;
$$;

CREATE TRIGGER trg_audit_adapters
    AFTER INSERT OR UPDATE OR DELETE ON adapters
    FOR EACH ROW EXECUTE FUNCTION fn_audit_adapters();