# ADR-001: PostgreSQL SKIP LOCKED as Task Queue

## Status

Accepted

## Context

Konduit needs a durable, transactional task queue to manage workflow step execution. Tasks must survive process crashes, support concurrent worker acquisition without duplicates, and maintain ACID guarantees for state transitions. The system targets hundreds to low-thousands of tasks per minute.

Common alternatives include dedicated message brokers (RabbitMQ, Redis Streams) or cloud-managed queues (AWS SQS). Each introduces additional infrastructure, operational complexity, and a separate data store that must be kept consistent with the primary database.

## Decision

Use PostgreSQL with `SELECT ... FOR UPDATE SKIP LOCKED` as the task queue implementation. Tasks are rows in the `tasks` table. Workers acquire tasks by selecting pending rows with `SKIP LOCKED`, which atomically skips rows already locked by other transactions — providing non-blocking, concurrent task acquisition.

Key implementation details:
- Partial indexes on `(status, next_retry_at)` for efficient pending task lookup
- Partial index on `(lock_timeout_at)` for orphan reclamation
- `batch_size` configuration for tuning acquisition granularity
- All task state transitions happen within database transactions

## Rationale

- **Single source of truth**: All workflow state (executions, tasks, dead letters) lives in one database. No distributed consistency problems between a queue and a database.
- **ACID guarantees**: Task acquisition, status updates, and retry scheduling are transactional. A crashed worker's uncommitted transaction is automatically rolled back by PostgreSQL.
- **No additional infrastructure**: The application only needs PostgreSQL — no message broker to deploy, monitor, or scale separately.
- **Sufficient throughput**: PostgreSQL with SKIP LOCKED handles thousands of row-level locks per second. This exceeds the target workload by a comfortable margin.
- **Mature ecosystem**: PostgreSQL's locking, indexing, and monitoring tools are well-understood and battle-tested.

## Trade-offs

**Gained:**
- Operational simplicity (single database)
- Transactional safety for all state transitions
- No message loss scenarios (WAL-backed durability)
- Familiar tooling for debugging (SQL queries, pg_stat_activity)

**Lost:**
- Lower throughput ceiling compared to dedicated message queues (RabbitMQ can handle 10K+ msg/s; Postgres SKIP LOCKED is practical up to ~5K tasks/s depending on hardware)
- No built-in pub/sub for real-time notification (mitigated by optional Redis signaling — see ADR-006)
- Database connection pool pressure from long-polling workers (mitigated by configurable poll intervals)
- Scaling the queue means scaling the entire database (acceptable for target scale)

