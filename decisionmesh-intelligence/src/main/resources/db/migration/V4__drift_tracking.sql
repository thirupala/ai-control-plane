CREATE TABLE drift_tracking (
    intent_id UUID PRIMARY KEY,
    last_drift DOUBLE PRECISION NOT NULL,
    updated_at TIMESTAMP NOT NULL
);