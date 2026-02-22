-- V11: Add metadata JSONB column to tasks table for persisting StepContext metadata between retries
ALTER TABLE tasks ADD COLUMN metadata JSONB;

