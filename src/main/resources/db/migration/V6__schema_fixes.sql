-- V6: Schema fixes to align with PRD definitions
-- Adds missing enum values, columns, and indexes.

-- 1. task_status enum: Add missing 'RUNNING' value
-- Note: CANCELLED is left in the enum (cannot remove Postgres enum values) but is not used in application code.
ALTER TYPE task_status ADD VALUE 'RUNNING' AFTER 'LOCKED';

-- 2. execution_status enum: Add missing 'TIMED_OUT' value
ALTER TYPE execution_status ADD VALUE 'TIMED_OUT' AFTER 'FAILED';

-- 3. executions table: Add missing columns
ALTER TABLE executions ADD COLUMN error TEXT;
ALTER TABLE executions ADD COLUMN timeout_at TIMESTAMPTZ;

-- Partial index for timeout checking on running executions
CREATE INDEX idx_executions_timeout ON executions (timeout_at)
    WHERE status = 'RUNNING';

-- 4. tasks table: Add missing columns
ALTER TABLE tasks ADD COLUMN parent_task_id UUID REFERENCES tasks(id);
ALTER TABLE tasks ADD COLUMN backoff_strategy VARCHAR(50) NOT NULL DEFAULT 'EXPONENTIAL';
ALTER TABLE tasks ADD COLUMN backoff_base_ms BIGINT NOT NULL DEFAULT 1000;
ALTER TABLE tasks ADD COLUMN timeout_at TIMESTAMPTZ;

-- 5. dead_letters table: Add missing columns
ALTER TABLE dead_letters ADD COLUMN error TEXT;
ALTER TABLE dead_letters ADD COLUMN attempts INT NOT NULL DEFAULT 0;

