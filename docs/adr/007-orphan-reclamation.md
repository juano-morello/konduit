# ADR-007: Lock-Timeout-Based Orphan Reclamation

## Status

Accepted

## Context

Workers acquire tasks by locking rows in the database (via `SELECT FOR UPDATE SKIP LOCKED`). If a worker crashes while holding a task lock, the database transaction is rolled back — but the task's in-memory state (status = `RUNNING`, `locked_by`, `lock_timeout_at`) may have already been committed in a previous transaction.

This creates "orphaned" tasks: tasks marked as RUNNING that no worker is actually executing. Without a reclamation mechanism, these tasks would be stuck indefinitely.

## Decision

Implement lock-timeout-based orphan reclamation:

1. When a worker acquires a task, it sets `lock_timeout_at = now() + lockTimeout` (default: 5 minutes).
2. A scheduled background job (`OrphanReclaimer`) periodically scans for tasks where `status = RUNNING` and `lock_timeout_at < now()`.
3. Orphaned tasks are reset to `PENDING` status with their attempt count preserved, making them eligible for re-acquisition by any worker.
4. The reclaimer runs on a configurable interval (`konduit.queue.reaper-interval`, default: 30 seconds).
5. Only the leader instance runs the reclaimer (via `LeaderOnlyScheduler`) to avoid duplicate work.

Workers also send periodic heartbeats to update their `last_heartbeat_at` timestamp. The `WorkerRegistry` tracks worker health, and stale workers (no heartbeat within `stale-threshold`) are marked as `STALE`. However, heartbeat status is **not** used for task reclamation — only `lock_timeout_at` determines orphan status.

## Rationale

- **Defense in depth**: Task safety does not depend on worker heartbeats. Even if the heartbeat mechanism fails, orphaned tasks are still reclaimed based on their lock timeout.
- **Simple and deterministic**: The reclamation logic is a single SQL query (`WHERE status = 'RUNNING' AND lock_timeout_at < now()`). No complex distributed protocol.
- **Bounded stuck time**: A task can be stuck for at most `lockTimeout + reaperInterval` before being reclaimed. With defaults, this is ~5.5 minutes.
- **Idempotent**: Reclaiming an already-reclaimed task is a no-op (status is already PENDING). Multiple reclaimer instances running concurrently is safe.

## Trade-offs

**Gained:**
- Guaranteed task reclamation without depending on worker health checks
- Simple, SQL-based implementation with no distributed coordination
- Bounded maximum stuck time (configurable via `lock-timeout` and `reaper-interval`)
- Works correctly even if Redis (leader election) is unavailable (all instances run reclaimer idempotently)

**Lost:**
- Tasks may wait up to `lockTimeout + reaperInterval` before being reclaimed (not instant detection)
- Lock timeout must be set longer than the longest expected task execution time (misconfiguration can cause premature reclamation of slow-but-healthy tasks)
- Adds a background scheduler that generates periodic database queries (minimal overhead but non-zero)
- Worker heartbeats provide health visibility but don't directly contribute to task safety (two separate mechanisms to understand)

