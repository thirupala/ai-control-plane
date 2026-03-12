CREATE TABLE intent_dependencies (
    parent_intent_id UUID NOT NULL,
    child_intent_id UUID NOT NULL,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (parent_intent_id, child_intent_id)
);