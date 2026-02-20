-- V1: Create workflows table (PRD §3.4.1)
-- Stores workflow definitions with their step graphs as JSONB.

CREATE TABLE workflows (
    id              UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(255)    NOT NULL,
    version         INT             NOT NULL DEFAULT 1,
    description     TEXT,
    step_definitions JSONB          NOT NULL,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT uq_workflows_name_version UNIQUE (name, version)
);

-- Index for lookup by name (most common query pattern)
CREATE INDEX idx_workflows_name ON workflows (name);

COMMENT ON TABLE workflows IS 'Workflow definitions with serialized step graphs';
COMMENT ON COLUMN workflows.step_definitions IS 'JSONB-serialized step graph (step names, types, ordering, retry policies)';

