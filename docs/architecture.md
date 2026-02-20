# Konduit Architecture

## Overview

Konduit is a modular monolith — all components run in a single Spring Boot process. PostgreSQL is the sole source of truth for all state (workflows, executions, tasks, dead letters, workers). Redis is an optional performance optimization for real-time signaling and leader election.

## Component Diagram

```
┌─────────────┐
│   Client     │
└──────┬───────┘
       │ REST/JSON
┌──────▼───────────────────────────────────────────────────────┐
│                     REST API Layer                            │
│  ExecutionController · WorkflowController · DeadLetterController │
│  WorkerController · StatsController                          │
└──────┬───────────────────────────────────────────────────────┘
       │
┌──────▼───────────────────────────────────────────────────────┐
│                    Execution Engine                           │
│  ExecutionEngine · ExecutionAdvancer · ExecutionStateMachine  │
│  TaskDispatcher · ExecutionTimeoutChecker                     │
└──────┬──────────────────────┬────────────────────────────────┘
       │                      │
┌──────▼──────────┐   ┌──────▼──────────┐
│  Workflow DSL    │   │   Task Queue     │
│  WorkflowBuilder│   │   TaskQueue      │
│  WorkflowRegistry│  │   DeadLetterQueue│
│  StepDefinition │   │   OrphanReclaimer│
│  ParallelBlock  │   └──────┬───────────┘
│  BranchBlock    │          │
└─────────────────┘   ┌──────▼──────────┐
                      │  Worker Pool     │
                      │  TaskWorker      │
                      │  WorkerRegistry  │
                      │  HeartbeatService│
                      └──────┬───────────┘
                             │
              ┌──────────────┼──────────────┐
       ┌──────▼──────┐             ┌────────▼────────┐
       │ PostgreSQL   │             │ Redis (optional) │
       │ Source of    │             │ Pub/Sub signaling│
       │ Truth        │             │ Leader election  │
       └──────────────┘             └──────────────────┘
```

## Module Structure

| Module | Package | Responsibility |
|--------|---------|---------------|
| **DSL** | `dev.konduit.dsl` | Kotlin DSL builders, workflow definitions, registry |
| **Engine** | `dev.konduit.engine` | Execution state machine, task dispatching, advancement |
| **Queue** | `dev.konduit.queue` | Task queue (SKIP LOCKED), dead letter queue, orphan reclaimer |
| **Worker** | `dev.konduit.worker` | Task worker with poll loop + thread pool, heartbeat, registry |
| **Coordination** | `dev.konduit.coordination` | Redis pub/sub signaling, leader election, NoOp fallbacks |
| **API** | `dev.konduit.api` | REST controllers, DTOs, error handling |
| **Observability** | `dev.konduit.observability` | Micrometer metrics, correlation filter, structured logging |
| **Persistence** | `dev.konduit.persistence` | JPA entities, Spring Data repositories |
| **Config** | `dev.konduit.config` | Redis configuration, health indicators |

## Data Flow

### Workflow Trigger → Completion

1. **Client** sends `POST /api/v1/executions` with workflow name and input
2. **ExecutionEngine** resolves the workflow definition from `WorkflowRegistry`
3. **ExecutionEngine** creates an `ExecutionEntity` (status: RUNNING) and dispatches the first element
4. **TaskDispatcher** creates `TaskEntity` rows (status: PENDING) for the current element
5. **TaskNotifier** (Redis or NoOp) signals workers that new tasks are available
6. **TaskWorker** acquires a pending task via `SELECT FOR UPDATE SKIP LOCKED`
7. **TaskWorker** executes the step handler, passing a `StepContext`
8. On success: task status → COMPLETED, output stored
9. **ExecutionAdvancer** checks if the current element is complete, then dispatches the next element
10. When all elements complete: execution status → COMPLETED
11. On failure: **RetryCalculator** computes next retry delay → task rescheduled or dead-lettered

### Parallel Execution (Fan-out/Fan-in)

When the engine encounters a `ParallelBlock`:
- All contained steps are dispatched simultaneously as separate tasks
- Workers acquire and execute them independently
- The engine waits for **all** parallel tasks to reach a terminal state
- Successful outputs are collected into `parallelOutputs` for the next step
- If any parallel task is dead-lettered, the execution fails (see [ADR-004](adr/004-parallel-failure-isolation.md))

### Conditional Branching

When the engine encounters a `BranchBlock`:
- The previous step's output is converted to a string
- The first matching `on()` condition selects the branch
- Tasks are created **only** for the matched branch (see [ADR-005](adr/005-conditional-branching.md))
- After the branch completes, execution continues to the next element

## Key Design Decisions

| Decision | ADR | Summary |
|----------|-----|---------|
| Postgres SKIP LOCKED as task queue | [ADR-001](adr/001-postgres-skip-locked.md) | Single database as source of truth, ACID guarantees |
| Kotlin DSL for workflow definitions | [ADR-002](adr/002-kotlin-dsl.md) | Compile-time safety, IDE support, no config files |
| Configurable backoff with jitter | [ADR-003](adr/003-retry-backoff-jitter.md) | Prevents thundering herd on retry storms |
| Parallel failure isolation | [ADR-004](adr/004-parallel-failure-isolation.md) | Failed steps don't cancel siblings |
| Lazy branch evaluation | [ADR-005](adr/005-conditional-branching.md) | Only matched branch tasks are created |
| Redis as optional signaling | [ADR-006](adr/006-redis-hybrid-signaling.md) | Graceful degradation to polling-only |
| Lock-timeout orphan reclamation | [ADR-007](adr/007-orphan-reclamation.md) | Defense in depth, no heartbeat dependency |

## Technology Stack

- **Language**: Kotlin 2.0 on Java 21
- **Framework**: Spring Boot 3.4
- **Database**: PostgreSQL 15+ (Flyway migrations, JPA/Hibernate)
- **Cache/Signaling**: Redis 7+ (optional)
- **Metrics**: Micrometer + Prometheus
- **Testing**: JUnit 5, Testcontainers, MockK
- **Build**: Gradle (Kotlin DSL)
- **Container**: Docker (multi-stage build)

