-- V9: Add partial index for retention cleanup (Feature 2.3)
-- Optimizes queries that find terminal-state executions older than a TTL cutoff.

CREATE INDEX idx_executions_retention ON executions (status, completed_at)
    WHERE status IN ('COMPLETED', 'FAILED', 'CANCELLED', 'TIMED_OUT')
      AND completed_at IS NOT NULL;

