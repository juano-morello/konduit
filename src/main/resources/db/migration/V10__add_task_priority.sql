ALTER TABLE tasks ADD COLUMN priority INT NOT NULL DEFAULT 0;

-- Partial index for efficient priority-ordered acquisition
CREATE INDEX idx_tasks_priority_pending ON tasks (status, priority DESC, created_at ASC)
    WHERE status = 'PENDING';

