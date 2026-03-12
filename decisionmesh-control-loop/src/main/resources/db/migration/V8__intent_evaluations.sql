CREATE TABLE intent_evaluations (
    intent_id UUID NOT NULL,
    satisfaction_score DOUBLE PRECISION NOT NULL,
    drift_score DOUBLE PRECISION NOT NULL,
    evaluated_at TIMESTAMP NOT NULL
);