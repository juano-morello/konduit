-- V7: Add optimistic locking version columns to tasks and executions tables.
-- JPA @Version uses this column to detect concurrent modifications.

ALTER TABLE tasks ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE executions ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

