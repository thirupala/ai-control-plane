CREATE TABLE governance_ledger (
    ledger_id UUID PRIMARY KEY,
    intent_id UUID NOT NULL,
    tenant_id VARCHAR NOT NULL,
    aggregate_version BIGINT NOT NULL,
    event_id UUID NOT NULL,
    event_type VARCHAR NOT NULL,
    policy_snapshot JSONB,
    budget_snapshot JSONB,
    sla_snapshot JSONB,
    previous_hash VARCHAR NOT NULL,
    current_hash VARCHAR NOT NULL,
    timestamp TIMESTAMP NOT NULL
);

CREATE TABLE policy_snapshots (
    intent_id UUID NOT NULL,
    version BIGINT NOT NULL,
    snapshot JSONB NOT NULL,
    PRIMARY KEY (intent_id, version)
);