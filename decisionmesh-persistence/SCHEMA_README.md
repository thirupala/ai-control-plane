# AI Governance Control Plane — Database Schema

## Overview

This is the complete PostgreSQL schema for the **Intent-Centric AI Governance Control Plane**. It is managed via Flyway and designed for Quarkus 3.x with Hibernate ORM Panache.

---

## Migration Files

| File | Purpose | Key Invariants |
|---|---|---|
| `V1__foundation_tenants_adapters.sql` | Tenants, adapters, utility functions | `fn_set_updated_at`, `fn_guard_immutable` |
| `V2__intent_domain_core.sql` | **Intent** aggregate root, plans, plan steps | Optimistic lock on `intents.version`, plan steps immutable |
| `V3__execution_layer.sql` | Execution records, spend, SLA, drift | All tables are append-only; UPDATE triggers reject mutations |
| `V4__policy_engine.sql` | Policy definitions, evaluation records | `policy_evaluations` append-only |
| `V5__learning_engine.sql` | Adapter performance profiles + version history | `adapter_profile_versions` immutable; `version` for optimistic locking |
| `V6__rate_limiting.sql` | Rate limit configs + durable audit counters | Redis is hot path; DB is audit trail + partition fallback |
| `V7__observability_events_audit.sql` | `intent_events` + `audit_log` | Both fully immutable; `global_seq` added in V10 |
| `V8__row_level_security.sql` | RLS policies for all tables | Requires `SET LOCAL app.current_tenant_id = '<uuid>'` |
| `V9__domain_functions_and_triggers.sql` | Phase guard, retry guard, auto-versioning | Belt-and-suspenders DB-level enforcement of domain invariants |
| `V10__analytics_views_and_partitioning.sql` | Views, composite indexes, partitioning guidance | `seq_global_event_order` for deterministic replay |

---

## Architectural Principles Enforced

### 1. Intent as Aggregate Root
`intents` is the parent FK target for all other entities. No entity can exist without an `intent_id` (except `tenants` and `adapters`).

### 2. Append-Only Immutable Records
The following tables have `UPDATE` rejected at the DB level via `fn_guard_immutable()`:
- `execution_records`
- `spend_records`
- `intent_plan_steps`
- `policy_evaluations`
- `adapter_profile_versions`
- `intent_events`
- `audit_log`

### 3. Optimistic Locking
Two tables use `@Version` / `version INT` for concurrent write safety:
- `intents` — updated by the orchestrator on every lifecycle transition
- `adapter_performance_profiles` — updated by the learning engine after each execution

### 4. Phase Transitions Enforced at Two Layers
- **Domain layer** (Java): `intent.startPlanning()`, `intent.markExecuting()`, etc.
- **DB layer** (trigger `trg_intent_phase_guard`): rejects any invalid `(phase_from → phase_to)` pair

Valid transition table:
```
SUBMITTED       → PLANNING, CANCELLED
PLANNING        → EXECUTING, CANCELLED, VIOLATED
EXECUTING       → EVALUATING, RETRY_SCHEDULED, CANCELLED, VIOLATED
EVALUATING      → SATISFIED, VIOLATED, RETRY_SCHEDULED
RETRY_SCHEDULED → PLANNING, CANCELLED
SATISFIED       → (terminal)
VIOLATED        → (terminal)
CANCELLED       → (terminal)
```

### 5. Tenant Isolation via RLS
Every table has:
- A `tenant_id UUID NOT NULL` column
- An RLS policy that filters on `fn_current_tenant_id()`
- `FORCE ROW LEVEL SECURITY` so even the table owner is subject to isolation

The application must call at transaction start:
```sql
SET LOCAL app.current_tenant_id = '<tenant-uuid>';
```

This is handled by `TenantContextInterceptor` in the Quarkus CDI layer.

### 6. Budget Enforcement
- `spend_records.cumulative_spend_usd` is populated by trigger `trg_spend_cumulative` at insert time (denormalised running total).
- `fn_would_exceed_budget(tenant_id, intent_id, proposed_cost)` is called by the execution engine before each adapter invocation.

### 7. SLA Enforcement
- `fn_intent_elapsed_ms(tenant_id, intent_id)` computes milliseconds elapsed since `intents.created_at`.
- `fn_is_sla_breached(tenant_id, intent_id)` returns `TRUE` if elapsed > `sla_deadline_ms`.
- `sla_windows` stores point-in-time snapshots at each evaluation checkpoint.

### 8. Plan Versioning
- `fn_auto_plan_version()` trigger auto-assigns `plan_version = MAX + 1` per intent.
- Previous active plan is set to `status = 'SUPERSEDED'` by the orchestrator when a replan occurs.

### 9. Attempt Numbering
- `fn_auto_attempt_number()` trigger auto-assigns `attempt_number = MAX + 1` per intent in `execution_records`.

---

## Key Relationships

```
tenants
  └── adapters
  └── intents  ◄─── aggregate root
        └── intent_plans  (versioned)
              └── intent_plan_steps  (immutable)
        └── execution_records  (append-only)
              └── spend_records  (append-only)
        └── sla_windows  (snapshots)
        └── intent_drift_evaluations  (append-only)
        └── policy_evaluations  (append-only, FK to policies)
        └── intent_events  (append-only event log)
  └── policies
  └── adapter_performance_profiles
        └── adapter_profile_versions  (immutable history)
  └── rate_limit_configs
        └── rate_limit_counters  (audit trail)
  └── audit_log  (cross-cutting)
```

---

## Running Migrations

```bash
# Dev (Quarkus DevServices starts Postgres automatically)
./mvnw quarkus:dev

# Production
./mvnw flyway:migrate \
  -Dflyway.url=jdbc:postgresql://host:5432/controlplane \
  -Dflyway.user=migration_user \
  -Dflyway.password=$MIGRATION_PASSWORD

# Validate only
./mvnw flyway:validate
```

---

## Partitioning (High Volume)

For tenants generating >10M rows/month, apply range partitioning to:

| Table | Partition Key | Recommended Interval |
|---|---|---|
| `execution_records` | `executed_at` | Monthly |
| `intent_events` | `occurred_at` | Monthly |
| `audit_log` | `occurred_at` | Monthly |
| `spend_records` | `recorded_at` | Monthly |

Use `pg_partman` for automated partition management. See the commented guidance in `V10`.

---

## Required PostgreSQL Extensions

| Extension | Purpose | Installed In |
|---|---|---|
| `pgcrypto` | `gen_random_uuid()` | V1 |
| `pg_stat_statements` | Query performance tracking | V1 |

---

## Security Notes

1. **Never** run application queries as the migration/superuser role.
2. The application role (`app_role`) has DML grants only — no DDL.
3. RLS policies enforce tenant isolation even if application-layer tenant filtering fails.
4. `fn_assert_tenant_context()` should be called at the start of every transaction.
5. Sensitive payload fields (`prompt`, `input`, `output`, etc.) must be masked before writing to `intent_events` and `audit_log`. The field list is configured in `application.properties` under `control-plane.observability.mask-payload-fields`.
