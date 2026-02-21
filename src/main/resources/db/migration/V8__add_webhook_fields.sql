-- V8: Add webhook callback fields to executions table
-- Supports optional webhook notifications when executions reach terminal states.
-- callback_status values: NONE, PENDING, DELIVERED, FAILED

ALTER TABLE executions ADD COLUMN callback_url VARCHAR(2048);
ALTER TABLE executions ADD COLUMN callback_status VARCHAR(20) DEFAULT 'NONE';

