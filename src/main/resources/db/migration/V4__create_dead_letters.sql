-- V4: Create dead_letters table (PRD §3.4.4)
-- Tasks that exhausted all retry attempts are moved here with full error history.

CREATE TABLE dead_letters (
    id              UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id         UUID            NOT NULL REFERENCES tasks(id),
    execution_id    UUID            NOT NULL REFERENCES executions(id),
    workflow_name   VARCHAR(255)    NOT NULL,
    step_name       VARCHAR(255)    NOT NULL,
    input           JSONB,
    error_history   JSONB           NOT NULL,
    reprocessed     BOOLEAN         NOT NULL DEFAULT FALSE,
    reprocessed_at  TIMESTAMPTZ,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT uq_dead_letters_task_id UNIQUE (task_id)
);

-- Index for browsing dead letters by execution
CREATE INDEX idx_dead_letters_execution_id ON dead_letters (execution_id);

-- Index for filtering by workflow (common in dead letter management UI/API)
CREATE INDEX idx_dead_letters_workflow_name ON dead_letters (workflow_name);

-- Partial index for unprocessed dead letters (the ones that need attention)
CREATE INDEX idx_dead_letters_unprocessed ON dead_letters (created_at DESC)
    WHERE reprocessed = FALSE;

-- Index for listing by creation time
CREATE INDEX idx_dead_letters_created_at ON dead_letters (created_at DESC);

COMMENT ON TABLE dead_letters IS 'Tasks that exhausted all retry attempts — preserved for debugging and reprocessing';
COMMENT ON COLUMN dead_letters.error_history IS 'JSONB array of attempt records: [{attempt, error, timestamp, duration_ms}]';
COMMENT ON COLUMN dead_letters.reprocessed IS 'Whether this dead letter has been resubmitted as a new task';

