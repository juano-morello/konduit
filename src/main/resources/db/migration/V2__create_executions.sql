-- V2: Create executions table (PRD §3.4.2)
-- Tracks workflow execution instances with their state, input/output, and timing.

CREATE TYPE execution_status AS ENUM (
    'PENDING',
    'RUNNING',
    'COMPLETED',
    'FAILED',
    'CANCELLED'
);

CREATE TABLE executions (
    id              UUID                PRIMARY KEY DEFAULT gen_random_uuid(),
    workflow_id     UUID                NOT NULL REFERENCES workflows(id),
    workflow_name   VARCHAR(255)        NOT NULL,
    workflow_version INT               NOT NULL,
    status          execution_status    NOT NULL DEFAULT 'PENDING',
    input           JSONB,
    output          JSONB,
    current_step    VARCHAR(255),
    idempotency_key VARCHAR(255),
    started_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ         NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ         NOT NULL DEFAULT now(),

    CONSTRAINT uq_executions_idempotency_key UNIQUE (idempotency_key)
);

-- Index for filtering by workflow
CREATE INDEX idx_executions_workflow_id ON executions (workflow_id);
CREATE INDEX idx_executions_workflow_name ON executions (workflow_name);

-- Index for filtering by status (common query: list running/failed executions)
CREATE INDEX idx_executions_status ON executions (status);

-- Partial index for active executions (PENDING or RUNNING) — used by engine and monitoring
CREATE INDEX idx_executions_active ON executions (status, created_at)
    WHERE status IN ('PENDING', 'RUNNING');

-- Index for listing executions ordered by creation time
CREATE INDEX idx_executions_created_at ON executions (created_at DESC);

COMMENT ON TABLE executions IS 'Workflow execution instances tracking state and progress';
COMMENT ON COLUMN executions.idempotency_key IS 'Optional client-provided key to prevent duplicate executions';
COMMENT ON COLUMN executions.current_step IS 'Name of the currently executing step (for progress tracking)';

