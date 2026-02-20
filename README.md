# Konduit

A durable, stateful workflow orchestration engine built with Kotlin, Spring Boot 3, and PostgreSQL.

Konduit demonstrates production-grade patterns for task queuing (PostgreSQL `SKIP LOCKED`), configurable retry semantics, dead letter handling, distributed worker coordination, and full observability — all in a clean, well-tested codebase.

## Features

- **Durable Task Queue** — PostgreSQL `SELECT FOR UPDATE SKIP LOCKED` for transactional, concurrent task acquisition with zero duplicates
- **Type-Safe Kotlin DSL** — Define workflows with `workflow {}`, `step {}`, `parallel {}`, `branch {}` builders with compile-time safety
- **Parallel Execution** — Fan-out/fan-in via `parallel {}` blocks with failure isolation ([ADR-004](docs/adr/004-parallel-failure-isolation.md))
- **Conditional Branching** — `branch {}` blocks with lazy evaluation — only matched branch tasks are created ([ADR-005](docs/adr/005-conditional-branching.md))
- **Configurable Retry** — Exponential, linear, or fixed backoff with optional jitter to prevent thundering herd ([ADR-003](docs/adr/003-retry-backoff-jitter.md))
- **Dead Letter Queue** — Failed tasks are dead-lettered with full error history; supports single and batch reprocessing
- **Distributed Workers** — Poll-based task acquisition with configurable concurrency, heartbeat monitoring, and graceful shutdown
- **Redis Coordination (Optional)** — Pub/sub for instant task notification + leader election; graceful degradation to polling-only ([ADR-006](docs/adr/006-redis-hybrid-signaling.md))
- **Observability** — Micrometer/Prometheus metrics, structured JSON logging with MDC correlation IDs, execution timeline API
- **Operational APIs** — REST endpoints for executions, workflows, dead letters, workers, and system stats

## Architecture

Konduit is a modular monolith — all components run in a single Spring Boot process. PostgreSQL is the sole source of truth. Redis is an optional performance optimization.

See [docs/architecture.md](docs/architecture.md) for the full architecture overview and component diagram.

### Module Structure

| Module | Responsibility |
|--------|---------------|
| `dsl` | Kotlin DSL builders, workflow definitions, registry |
| `engine` | Execution state machine, task dispatching, advancement |
| `queue` | Task queue (SKIP LOCKED), dead letter queue, orphan reclaimer |
| `worker` | Task worker with poll loop + thread pool, heartbeat, registry |
| `coordination` | Redis pub/sub signaling, leader election, NoOp fallbacks |
| `api` | REST controllers, DTOs, error handling |
| `observability` | Micrometer metrics, correlation filter, structured logging |
| `persistence` | JPA entities, Spring Data repositories |
| `config` | Redis configuration, health indicators |

## Quick Start

### Prerequisites

- Docker and Docker Compose
- Java 21+ (for local development)

### Run with Docker Compose

```bash
# Start PostgreSQL, Redis, and the application
docker-compose up -d

# Verify health
curl http://localhost:8080/actuator/health

# List registered workflows
curl http://localhost:8080/api/v1/workflows

# Trigger the NPO onboarding workflow
curl -X POST http://localhost:8080/api/v1/executions \
  -H 'Content-Type: application/json' \
  -d '{"workflowName": "npo-onboarding", "input": {"orgName": "Helping Hands Foundation", "ein": "12-3456789"}}'

# Check execution status (replace {id} with the returned execution ID)
curl http://localhost:8080/api/v1/executions/{id}

# View execution timeline
curl http://localhost:8080/api/v1/executions/{id}/timeline

# Check system stats
curl http://localhost:8080/api/v1/stats
```

### Run Locally (Development)

```bash
# Start only infrastructure
docker-compose up -d postgres redis

# Run the application
./gradlew bootRun

# Or run tests
./gradlew test
```

## Workflow DSL Guide

Workflows are defined as Spring `@Bean` methods that return `WorkflowDefinition` objects. The `WorkflowRegistry` automatically collects and registers all definitions at startup.

### Sequential Workflow

```kotlin
@Bean
fun myWorkflow(): WorkflowDefinition = workflow("my-workflow") {
    version(1)
    description("A simple sequential workflow")

    step("validate") {
        handler { ctx ->
            val input = ctx.input as? Map<*, *> ?: emptyMap<String, Any>()
            mapOf("validated" to true, "data" to input)
        }
        retryPolicy {
            maxAttempts(3)
            backoff(BackoffStrategy.EXPONENTIAL)
            baseDelay(1000)
            jitter(true)
        }
    }

    step("process") {
        handler { ctx -> mapOf("processed" to true) }
    }
}
```

### Parallel Workflow (Fan-out/Fan-in)

```kotlin
@Bean
fun enrichmentWorkflow(): WorkflowDefinition = workflow("data-enrichment") {
    step("fetch-data") {
        handler { ctx -> mapOf("rawData" to "fetched") }
    }

    parallel {
        step("enrich-a") { handler { ctx -> mapOf("a" to "enriched") } }
        step("enrich-b") { handler { ctx -> mapOf("b" to "enriched") } }
        step("enrich-c") { handler { ctx -> mapOf("c" to "enriched") } }
    }

    // parallelOutputs contains results keyed by step name
    step("merge") {
        handler { ctx ->
            val outputs = ctx.parallelOutputs
            mapOf("merged" to outputs)
        }
    }
}
```

### Conditional Branching

```kotlin
@Bean
fun branchWorkflow(): WorkflowDefinition = workflow("risk-assessment") {
    step("evaluate") {
        handler { ctx -> "HIGH" } // Output determines branch
    }

    branch("risk-level") {
        on("LOW") {
            step("fast-track") { handler { ctx -> mapOf("approved" to true) } }
        }
        on("HIGH") {
            step("deep-review") { handler { ctx -> mapOf("reviewed" to true) } }
            step("escalate") { handler { ctx -> mapOf("escalated" to true) } }
        }
        otherwise {
            step("manual-review") { handler { ctx -> mapOf("manual" to true) } }
        }
    }

    step("finalize") {
        handler { ctx -> mapOf("done" to true) }
    }
}
```


## REST API Reference

All endpoints are prefixed with `/api/v1`.

### Executions

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/executions` | Trigger a new workflow execution |
| `GET` | `/executions` | List executions (filterable by `status`, `workflowName`; paginated) |
| `GET` | `/executions/{id}` | Get execution by ID |
| `GET` | `/executions/{id}/tasks` | List tasks for an execution |
| `GET` | `/executions/{id}/timeline` | Get execution timeline with step-level detail |
| `POST` | `/executions/{id}/cancel` | Cancel a running execution |

**Trigger execution:**
```bash
curl -X POST http://localhost:8080/api/v1/executions \
  -H 'Content-Type: application/json' \
  -d '{
    "workflowName": "npo-onboarding",
    "input": {"orgName": "Test Org"},
    "idempotencyKey": "optional-unique-key"
  }'
```

**Response:**
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "workflowName": "npo-onboarding",
  "workflowVersion": 1,
  "status": "RUNNING",
  "input": {"orgName": "Test Org"},
  "currentStep": "validate",
  "createdAt": "2026-02-20T12:00:00Z",
  "startedAt": "2026-02-20T12:00:00Z"
}
```

### Workflows

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/workflows` | List all registered workflows |
| `GET` | `/workflows/{name}` | Get workflow by name (latest version) |

### Dead Letters

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/dead-letters` | List dead letters (filterable by `workflowName`, `executionId`; paginated) |
| `GET` | `/dead-letters/{id}` | Get dead letter by ID |
| `POST` | `/dead-letters/{id}/reprocess` | Reprocess a single dead letter |
| `POST` | `/dead-letters/reprocess-batch` | Batch reprocess dead letters matching filter |

### Workers

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/workers` | List all registered workers |

### Stats

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/stats` | Get system statistics (executions, tasks, workers, queue depth, throughput) |

### Actuator Endpoints

| Endpoint | Description |
|----------|-------------|
| `/actuator/health` | Health check (includes Postgres, Redis, custom indicators) |
| `/actuator/prometheus` | Prometheus metrics endpoint |
| `/actuator/metrics` | Spring Boot metrics |
| `/actuator/info` | Application info |

## Error Responses

All API errors return a consistent JSON structure via the `GlobalExceptionHandler`:

```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "workflowName must not be blank",
  "timestamp": "2026-02-20T12:00:00Z",
  "path": "/api/v1/executions"
}
```

| Field | Type | Description |
|-------|------|-------------|
| `status` | `int` | HTTP status code |
| `error` | `string` | HTTP reason phrase (e.g., "Bad Request", "Not Found") |
| `message` | `string` | Human-readable error detail |
| `timestamp` | `string` | ISO 8601 timestamp of when the error occurred |
| `path` | `string` | Request URI that caused the error |

### Common Error Codes

**400 Bad Request** — Invalid input or missing required fields:
```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "workflowName must not be blank",
  "timestamp": "2026-02-20T12:00:00Z",
  "path": "/api/v1/executions"
}
```

**404 Not Found** — Resource does not exist:
```json
{
  "status": 404,
  "error": "Not Found",
  "message": "Execution not found: 550e8400-e29b-41d4-a716-446655440000",
  "timestamp": "2026-02-20T12:00:00Z",
  "path": "/api/v1/executions/550e8400-e29b-41d4-a716-446655440000"
}
```

**409 Conflict** — Invalid state transition or duplicate idempotency key:
```json
{
  "status": 409,
  "error": "Conflict",
  "message": "Execution is already in terminal state: COMPLETED",
  "timestamp": "2026-02-20T12:00:00Z",
  "path": "/api/v1/executions/550e8400-e29b-41d4-a716-446655440000/cancel"
}
```

**500 Internal Server Error** — Unexpected server-side failure:
```json
{
  "status": 500,
  "error": "Internal Server Error",
  "message": "An unexpected error occurred",
  "timestamp": "2026-02-20T12:00:00Z",
  "path": "/api/v1/executions"
}
```

## Configuration Reference

All configuration properties are under the `konduit.*` prefix in `application.yml`.

### Worker Configuration (`konduit.worker.*`)

| Property | Default | Description |
|----------|---------|-------------|
| `concurrency` | `5` | Number of concurrent tasks a single worker can execute |
| `heartbeat-interval` | `10s` | Interval between heartbeat updates |
| `drain-timeout` | `30s` | Time to wait for in-progress tasks during graceful shutdown |
| `stale-threshold` | `60s` | Threshold after which a worker is considered stale |
| `poll-interval` | `1s` | Interval between polling attempts when no tasks are available |

### Queue Configuration (`konduit.queue.*`)

| Property | Default | Description |
|----------|---------|-------------|
| `lock-timeout` | `5m` | Duration after which a locked task is considered orphaned |
| `reaper-interval` | `30s` | Interval for the orphan reclaimer to check for stuck tasks |
| `batch-size` | `1` | Maximum number of tasks to acquire in a single poll |

### Leader Election (`konduit.leader.*`)

| Property | Default | Description |
|----------|---------|-------------|
| `lock-ttl` | `30s` | TTL for the leader election lock in Redis |
| `renew-interval` | `10s` | Interval for renewing the leader lock |
| `lock-key` | `konduit:leader` | Redis key used for leader election lock |

### Execution (`konduit.execution.*`)

| Property | Default | Description |
|----------|---------|-------------|
| `default-timeout` | `30m` | Default timeout for workflow executions |
| `timeout-check-interval` | `30s` | Interval for checking timed-out executions |

### Redis (`konduit.redis.*`)

| Property | Default | Description |
|----------|---------|-------------|
| `enabled` | `true` | Whether Redis coordination is enabled |
| `channel` | `konduit:tasks` | Redis pub/sub channel for task notifications |

### Retry Defaults (`konduit.retry.*`)

| Property | Default | Description |
|----------|---------|-------------|
| `max-attempts` | `3` | Default maximum retry attempts |
| `initial-delay` | `1s` | Default initial delay for retry backoff |
| `max-delay` | `5m` | Default maximum delay for retry backoff |
| `multiplier` | `2.0` | Default backoff multiplier |

### Observability (`konduit.metrics.*`, `konduit.logging.*`)

| Property | Default | Description |
|----------|---------|-------------|
| `metrics.enabled` | `true` | Whether Prometheus metrics collection is enabled |
| `logging.include-payload` | `false` | Whether to include request/response payloads in log output |

## Testing

### Running Tests

```bash
# Run the full test suite (unit + integration)
./gradlew test

# Run with verbose output
./gradlew test --info

# Run a specific test class
./gradlew test --tests "dev.konduit.engine.ExecutionEngineIntegrationTest"
```

### Requirements

- **Docker**: Integration tests use [Testcontainers](https://testcontainers.com/) to spin up PostgreSQL instances automatically. Docker must be running.
- **No external services needed**: Tests are fully self-contained — no manual database setup required.

### Test Coverage

The test suite includes 111 tests covering:
- **Unit tests**: DSL builders, retry calculation, backoff strategies
- **Integration tests**: Full workflow execution (sequential, parallel, branching), task queue concurrency, dead letter handling, worker lifecycle, API endpoints, metrics, and health checks

## Observability

### Prometheus Metrics

Available at `/actuator/prometheus`. Key Konduit-specific metrics:

| Metric | Type | Description |
|--------|------|-------------|
| `konduit_executions_triggered_total` | Counter | Total workflow executions triggered |
| `konduit_executions_completed_total` | Counter | Total workflow executions completed |
| `konduit_executions_failed_total` | Counter | Total workflow executions failed |
| `konduit_tasks_completed_total` | Counter | Total tasks completed |
| `konduit_tasks_failed_total` | Counter | Total tasks failed |
| `konduit_tasks_dead_lettered_total` | Counter | Total tasks sent to dead letter queue |
| `konduit_task_execution_duration_seconds` | Histogram | Task execution duration |
| `konduit_queue_depth` | Gauge | Current number of pending tasks |
| `konduit_active_workers` | Gauge | Number of active workers |

### Structured Logging

Logs are output in structured JSON format (via Logstash Logback encoder) with MDC correlation:

- `executionId`: Workflow execution ID
- `taskId`: Current task ID
- `workflowName`: Workflow name
- `stepName`: Current step name

### Health Checks

`/actuator/health` includes:
- PostgreSQL connectivity
- Redis connectivity (when enabled)
- Custom Konduit health indicators (worker status, queue health)

## Security Boundaries

> **Important**: Konduit is a portfolio/demonstration project. The following security features are **not implemented** and would need to be added for production use:

| Feature | Status | What Would Be Needed |
|---------|--------|---------------------|
| **Authentication** | ❌ Not implemented | Spring Security with JWT or OAuth2; API key authentication for service-to-service calls |
| **Authorization** | ❌ Not implemented | Role-based access control (RBAC) for API endpoints; workflow-level permissions |
| **Multi-tenancy** | ❌ Not implemented | Tenant isolation at the database level (row-level security or schema-per-tenant); tenant context propagation |
| **Rate Limiting** | ❌ Not implemented | API rate limiting (e.g., Spring Cloud Gateway or Bucket4j); per-tenant quotas |
| **Input Validation** | ⚠️ Basic only | Request body validation via Bean Validation; would need deeper input sanitization for untrusted data |
| **Audit Logging** | ❌ Not implemented | Immutable audit trail for all state changes; who triggered what, when |
| **Encryption** | ❌ Not implemented | TLS termination (typically at load balancer); encryption at rest for sensitive workflow inputs |
| **CORS** | ❌ Not configured | Would need explicit CORS configuration if accessed from browser clients |

## Architecture Decision Records

All architectural decisions are documented in the `docs/adr/` directory:

| ADR | Title |
|-----|-------|
| [ADR-001](docs/adr/001-postgres-skip-locked.md) | PostgreSQL SKIP LOCKED as Task Queue |
| [ADR-002](docs/adr/002-kotlin-dsl.md) | Type-Safe Kotlin DSL for Workflow Definitions |
| [ADR-003](docs/adr/003-retry-backoff-jitter.md) | Configurable Backoff with Jitter for Retries |
| [ADR-004](docs/adr/004-parallel-failure-isolation.md) | Parallel Step Failure Isolation |
| [ADR-005](docs/adr/005-conditional-branching.md) | Lazy Evaluation for Conditional Branching |
| [ADR-006](docs/adr/006-redis-hybrid-signaling.md) | Redis as Optional Hybrid Signaling Layer |
| [ADR-007](docs/adr/007-orphan-reclamation.md) | Lock-Timeout-Based Orphan Reclamation |
| [ADR-008](docs/adr/008-postgres-enum-mapping.md) | PostgreSQL Custom Enum Type Mapping |

## Technology Stack

- **Language**: Kotlin 2.0 on Java 21
- **Framework**: Spring Boot 3.4 (Web, Data JPA, Data Redis, Actuator, Validation)
- **Database**: PostgreSQL 15+ with Flyway migrations
- **Cache/Signaling**: Redis 7+ (optional)
- **Metrics**: Micrometer + Prometheus
- **Logging**: Logback with Logstash JSON encoder
- **Testing**: JUnit 5, Testcontainers, MockK, SpringMockK
- **Build**: Gradle with Kotlin DSL
- **Container**: Docker (multi-stage build)

## License

This is a portfolio project for demonstration purposes.