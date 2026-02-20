# ADR-006: Redis as Optional Hybrid Signaling Layer

## Status

Accepted

## Context

Workers need to know when new tasks are available. Two fundamental approaches exist:

1. **Polling**: Workers periodically query the database for pending tasks. Simple but introduces latency (up to one poll interval) and generates constant database load.
2. **Push notification**: A signaling mechanism notifies workers immediately when tasks are dispatched. Lower latency but requires additional infrastructure.

Konduit also needs distributed coordination for leader election (ensuring only one instance runs background schedulers like orphan reclamation and execution timeout checking).

## Decision

Use Redis as an **optional** signaling and coordination layer with graceful degradation:

- **`RedisTaskNotifier`**: Publishes to a Redis pub/sub channel when new tasks are dispatched. Workers subscribe and wake up immediately.
- **`RedisLeaderElection`**: Uses `SET key NX EX ttl` for distributed leader election with automatic expiry.
- **`NoOpTaskNotifier`**: Fallback when Redis is unavailable — workers rely solely on polling.
- **`NoOpLeaderElection`**: Fallback — all instances run background jobs (idempotent by design).

Redis availability is checked at startup. If Redis is unreachable or `konduit.redis.enabled=false`, the application starts normally with NoOp implementations.

## Rationale

- **Best of both worlds**: When Redis is available, task pickup latency drops from seconds (poll interval) to milliseconds (pub/sub). When Redis is down, the system is still correct — just slower.
- **No hard dependency**: The application can run with just PostgreSQL. Redis is a performance optimization, not a correctness requirement.
- **Simple coordination**: Redis `SET NX EX` is a well-understood pattern for leader election that doesn't require consensus protocols or ZooKeeper.
- **Operational flexibility**: Development environments can skip Redis entirely. Production can add Redis for better performance.

## Trade-offs

**Gained:**
- Sub-millisecond task notification when Redis is available
- Simple, reliable leader election with automatic failover (lock expiry)
- System correctness without Redis (graceful degradation)
- Flexible deployment topology (Postgres-only or Postgres+Redis)

**Lost:**
- Two code paths to maintain and test (Redis and NoOp implementations)
- Slightly more complex configuration (Redis connection settings, channel names, TTLs)
- Redis pub/sub is fire-and-forget — if a worker misses a notification, it falls back to polling (acceptable since polling is the baseline)
- Leader election has a gap of up to `lock-ttl` seconds during failover (new leader must wait for old lock to expire)

