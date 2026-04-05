-- ─────────────────────────────────────────────────────────────────────────────
-- V3__intent_cascade_delete.sql
--
-- Adds ON DELETE CASCADE to every FK that references the intents table.
-- Without this, deleting an intent with any child rows (plans, executions,
-- spend records, etc.) throws a foreign key constraint violation.
--
-- The pattern: drop the existing FK constraint, re-add it with ON DELETE CASCADE.
-- All constraint names are taken from V1__decision_mesh.sql.
--
-- Deletion order guaranteed by CASCADE (PostgreSQL handles it automatically):
--   policy_evaluations → intent_plan_steps → spend_records → execution_records
--   → intent_drift_evaluations → sla_windows → intent_plans → intents
-- ─────────────────────────────────────────────────────────────────────────────

-- ── intent_plans ─────────────────────────────────────────────────────────────
ALTER TABLE intent_plans
DROP CONSTRAINT IF EXISTS intent_plans_intent_id_fkey,
    ADD  CONSTRAINT intent_plans_intent_id_fkey
        FOREIGN KEY (intent_id) REFERENCES intents(id) ON DELETE CASCADE;

ALTER TABLE intent_plans
DROP CONSTRAINT IF EXISTS intent_plans_tenant_id_fkey,
    ADD  CONSTRAINT intent_plans_tenant_id_fkey
        FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE;

-- ── intent_plan_steps ─────────────────────────────────────────────────────────
ALTER TABLE intent_plan_steps
DROP CONSTRAINT IF EXISTS intent_plan_steps_intent_id_fkey,
    ADD  CONSTRAINT intent_plan_steps_intent_id_fkey
        FOREIGN KEY (intent_id) REFERENCES intents(id) ON DELETE CASCADE;

ALTER TABLE intent_plan_steps
DROP CONSTRAINT IF EXISTS intent_plan_steps_plan_id_fkey,
    ADD  CONSTRAINT intent_plan_steps_plan_id_fkey
        FOREIGN KEY (plan_id) REFERENCES intent_plans(id) ON DELETE CASCADE;

ALTER TABLE intent_plan_steps
DROP CONSTRAINT IF EXISTS intent_plan_steps_tenant_id_fkey,
    ADD  CONSTRAINT intent_plan_steps_tenant_id_fkey
        FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE;

-- ── execution_records ─────────────────────────────────────────────────────────
ALTER TABLE execution_records
DROP CONSTRAINT IF EXISTS execution_records_intent_id_fkey,
    ADD  CONSTRAINT execution_records_intent_id_fkey
        FOREIGN KEY (intent_id) REFERENCES intents(id) ON DELETE CASCADE;

ALTER TABLE execution_records
DROP CONSTRAINT IF EXISTS execution_records_plan_id_fkey,
    ADD  CONSTRAINT execution_records_plan_id_fkey
        FOREIGN KEY (plan_id) REFERENCES intent_plans(id) ON DELETE CASCADE;

ALTER TABLE execution_records
DROP CONSTRAINT IF EXISTS execution_records_plan_step_id_fkey,
    ADD  CONSTRAINT execution_records_plan_step_id_fkey
        FOREIGN KEY (plan_step_id) REFERENCES intent_plan_steps(id) ON DELETE CASCADE;

ALTER TABLE execution_records
DROP CONSTRAINT IF EXISTS execution_records_tenant_id_fkey,
    ADD  CONSTRAINT execution_records_tenant_id_fkey
        FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE;

-- ── spend_records ─────────────────────────────────────────────────────────────
ALTER TABLE spend_records
DROP CONSTRAINT IF EXISTS spend_records_intent_id_fkey,
    ADD  CONSTRAINT spend_records_intent_id_fkey
        FOREIGN KEY (intent_id) REFERENCES intents(id) ON DELETE CASCADE;

ALTER TABLE spend_records
DROP CONSTRAINT IF EXISTS spend_records_execution_id_fkey,
    ADD  CONSTRAINT spend_records_execution_id_fkey
        FOREIGN KEY (execution_id) REFERENCES execution_records(id) ON DELETE CASCADE;

ALTER TABLE spend_records
DROP CONSTRAINT IF EXISTS spend_records_tenant_id_fkey,
    ADD  CONSTRAINT spend_records_tenant_id_fkey
        FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE;

-- ── policy_evaluations ────────────────────────────────────────────────────────
ALTER TABLE policy_evaluations
DROP CONSTRAINT IF EXISTS policy_evaluations_intent_id_fkey,
    ADD  CONSTRAINT policy_evaluations_intent_id_fkey
        FOREIGN KEY (intent_id) REFERENCES intents(id) ON DELETE CASCADE;

ALTER TABLE policy_evaluations
DROP CONSTRAINT IF EXISTS policy_evaluations_tenant_id_fkey,
    ADD  CONSTRAINT policy_evaluations_tenant_id_fkey
        FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE;

-- ── sla_windows ───────────────────────────────────────────────────────────────
ALTER TABLE sla_windows
DROP CONSTRAINT IF EXISTS sla_windows_intent_id_fkey,
    ADD  CONSTRAINT sla_windows_intent_id_fkey
        FOREIGN KEY (intent_id) REFERENCES intents(id) ON DELETE CASCADE;

ALTER TABLE sla_windows
DROP CONSTRAINT IF EXISTS sla_windows_tenant_id_fkey,
    ADD  CONSTRAINT sla_windows_tenant_id_fkey
        FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE;

-- ── intent_drift_evaluations ──────────────────────────────────────────────────
ALTER TABLE intent_drift_evaluations
DROP CONSTRAINT IF EXISTS intent_drift_evaluations_intent_id_fkey,
    ADD  CONSTRAINT intent_drift_evaluations_intent_id_fkey
        FOREIGN KEY (intent_id) REFERENCES intents(id) ON DELETE CASCADE;

ALTER TABLE intent_drift_evaluations
DROP CONSTRAINT IF EXISTS intent_drift_evaluations_execution_id_fkey,
    ADD  CONSTRAINT intent_drift_evaluations_execution_id_fkey
        FOREIGN KEY (execution_id) REFERENCES execution_records(id) ON DELETE CASCADE;

ALTER TABLE intent_drift_evaluations
DROP CONSTRAINT IF EXISTS intent_drift_evaluations_tenant_id_fkey,
    ADD  CONSTRAINT intent_drift_evaluations_tenant_id_fkey
        FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE;