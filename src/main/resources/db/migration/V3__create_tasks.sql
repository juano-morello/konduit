-- V3: Create tasks table (PRD §3.4.3)
-- The task queue: each row is a unit of work. Workers acquire tasks via SKIP LOCKED.

CREATE TYPE task_status AS ENUM (
    'PENDING',
    'LOCKED',
    'COMPLETED',
    'FAILED',
    'DEAD_LETTER',
    'CANCELLED'
);

CREATE TYPE step_type AS ENUM (
    'SEQUENTIAL',
    'PARALLEL',
    'BRANCH'
);

CREATE TABLE tasks (
    id              UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    execution_id    UUID            NOT NULL REFERENCES executions(id),
    step_name       VARCHAR(255)    NOT NULL,
    step_type       step_type       NOT NULL DEFAULT 'SEQUENTIAL',
    step_order      INT             NOT NULL DEFAULT 0,
    status          task_status     NOT NULL DEFAULT 'PENDING',
    input           JSONB,
    output          JSONB,
    error           TEXT,
    attempt         INT             NOT NULL DEFAULT 0,
    max_attempts    INT             NOT NULL DEFAULT 3,
    next_retry_at   TIMESTAMPTZ,
    locked_by       VARCHAR(255),
    locked_at       TIMESTAMPTZ,
    lock_timeout_at TIMESTAMPTZ,
    started_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),

    -- Parallel fan-out/fan-in tracking (Phase 2)
    parallel_group  VARCHAR(255),
    branch_key      VARCHAR(255)
);

-- Index for task lookup by execution (list tasks for an execution)
CREATE INDEX idx_tasks_execution_id ON tasks (execution_id);

-- CRITICAL: Partial index for SKIP LOCKED task acquisition (PRD §6.1)
-- Workers poll for PENDING tasks that are ready to execute (no retry delay or delay has passed)
CREATE INDEX idx_tasks_acquirable ON tasks (status, next_retry_at)
    WHERE status = 'PENDING';

-- Partial index for orphan reclamation: find LOCKED tasks past their timeout (PRD §6.3)
CREATE INDEX idx_tasks_locked_timeout ON tasks (lock_timeout_at)
    WHERE status = 'LOCKED';

-- Index for retry scheduling: find FAILED tasks ready for retry
CREATE INDEX idx_tasks_retry ON tasks (status, next_retry_at)
    WHERE status = 'FAILED';

-- Index for execution step ordering
CREATE INDEX idx_tasks_execution_step ON tasks (execution_id, step_order);

-- Index for parallel group tracking (Phase 2 fan-in)
CREATE INDEX idx_tasks_parallel_group ON tasks (execution_id, parallel_group)
    WHERE parallel_group IS NOT NULL;

COMMENT ON TABLE tasks IS 'Task queue: units of work acquired by workers via SELECT FOR UPDATE SKIP LOCKED';
COMMENT ON COLUMN tasks.locked_by IS 'Worker ID that currently holds the lock on this task';
COMMENT ON COLUMN tasks.lock_timeout_at IS 'When the lock expires — orphan reclaimer resets tasks past this time';
COMMENT ON COLUMN tasks.next_retry_at IS 'Earliest time this task can be retried (null = immediately eligible)';
COMMENT ON COLUMN tasks.parallel_group IS 'Groups parallel tasks for fan-in completion tracking';
COMMENT ON COLUMN tasks.branch_key IS 'The branch condition value that selected this task';

