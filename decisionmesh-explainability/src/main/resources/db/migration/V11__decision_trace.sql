CREATE TABLE decision_traces (
    decision_id UUID PRIMARY KEY,
    intent_id UUID NOT NULL,
    tenant_id VARCHAR NOT NULL,
    decision_type VARCHAR NOT NULL,
    inputs_snapshot JSONB,
    scoring_snapshot JSONB,
    policy_snapshot JSONB,
    portfolio_context JSONB,
    rationale TEXT,
    timestamp TIMESTAMP NOT NULL
);

CREATE TABLE decision_trace_links (
    parent_decision_id UUID NOT NULL,
    child_decision_id UUID NOT NULL,
    PRIMARY KEY (parent_decision_id, child_decision_id)
);