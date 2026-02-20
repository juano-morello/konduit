-- V5: Create workers table (PRD §3.4.5)
-- Tracks registered worker instances, their health via heartbeat, and capacity.

CREATE TYPE worker_status AS ENUM (
    'ACTIVE',
    'DRAINING',
    'STOPPED'
);

CREATE TABLE workers (
    id              UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    worker_id       VARCHAR(255)    NOT NULL UNIQUE,
    status          worker_status   NOT NULL DEFAULT 'ACTIVE',
    hostname        VARCHAR(255),
    concurrency     INT             NOT NULL DEFAULT 5,
    active_tasks    INT             NOT NULL DEFAULT 0,
    last_heartbeat  TIMESTAMPTZ     NOT NULL DEFAULT now(),
    started_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    stopped_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT now()
);

-- Index for stale worker detection: find workers with old heartbeats
CREATE INDEX idx_workers_heartbeat ON workers (last_heartbeat)
    WHERE status = 'ACTIVE';

-- Index for listing active workers
CREATE INDEX idx_workers_status ON workers (status);

COMMENT ON TABLE workers IS 'Registered worker instances with heartbeat tracking and capacity info';
COMMENT ON COLUMN workers.worker_id IS 'Unique logical worker identifier (e.g., hostname + PID)';
COMMENT ON COLUMN workers.last_heartbeat IS 'Last heartbeat timestamp — stale detection uses this';
COMMENT ON COLUMN workers.active_tasks IS 'Current number of tasks being processed by this worker';
COMMENT ON COLUMN workers.concurrency IS 'Maximum number of concurrent tasks this worker can handle';

