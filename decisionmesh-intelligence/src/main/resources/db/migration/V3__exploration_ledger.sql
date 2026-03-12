CREATE TABLE exploration_ledger (
    entry_id UUID PRIMARY KEY,
    adapter_id VARCHAR NOT NULL,
    intent_type VARCHAR NOT NULL,
    exploration BOOLEAN NOT NULL,
    reward DOUBLE PRECISION NOT NULL,
    regret DOUBLE PRECISION NOT NULL,
    confidence DOUBLE PRECISION NOT NULL,
    timestamp TIMESTAMP NOT NULL
);