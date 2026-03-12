CREATE TABLE intent_portfolios (
    tenant_id VARCHAR PRIMARY KEY,
    total_budget DOUBLE PRECISION NOT NULL,
    risk_threshold DOUBLE PRECISION NOT NULL
);

CREATE TABLE portfolio_intents (
    tenant_id VARCHAR NOT NULL,
    intent_id UUID NOT NULL,
    priority_weight DOUBLE PRECISION NOT NULL,
    PRIMARY KEY (tenant_id, intent_id)
);