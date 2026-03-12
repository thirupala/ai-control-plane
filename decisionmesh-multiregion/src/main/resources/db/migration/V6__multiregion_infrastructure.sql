CREATE TABLE intent_region_registry (
    intent_id UUID PRIMARY KEY,
    tenant_id VARCHAR NOT NULL,
    home_region VARCHAR NOT NULL,
    failover_region VARCHAR,
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE global_idempotency (
    idempotency_key VARCHAR PRIMARY KEY,
    created_at TIMESTAMP NOT NULL
);