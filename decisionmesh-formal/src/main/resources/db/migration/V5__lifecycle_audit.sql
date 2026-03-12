CREATE TABLE lifecycle_audit (
    intent_id UUID NOT NULL,
    phase VARCHAR NOT NULL,
    action VARCHAR NOT NULL,
    version BIGINT NOT NULL,
    timestamp TIMESTAMP NOT NULL
);