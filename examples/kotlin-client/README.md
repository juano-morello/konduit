# Konduit Kotlin Client Example

A standalone Kotlin script demonstrating how to interact with the Konduit Workflow Engine REST API using only JDK 21 built-in classes (`java.net.http.HttpClient`). No external dependencies required.

## Features Demonstrated

1. **List workflows** — Discover available workflow definitions
2. **Trigger execution** — Start a new workflow execution with input data and idempotency key
3. **Poll for completion** — Periodically check execution status until terminal state
4. **Read result** — Fetch the completed execution details
5. **View timeline** — Inspect step-by-step execution timeline
6. **View tasks** — List individual tasks and their statuses

## Prerequisites

- **JDK 21+** (for `java.net.http.HttpClient` and Kotlin script support)
- **Kotlin CLI** (`kotlin` command) — install via [SDKMAN](https://sdkman.io/) or [Homebrew](https://brew.sh/)
- **Konduit running** — via Docker Compose or `./gradlew bootRun`

## Quick Start

### 1. Start Konduit

```bash
# From the project root:
docker compose up -d

# Or run directly:
./gradlew bootRun
```

### 2. Run the Client

```bash
cd examples/kotlin-client

# Default: connects to http://localhost:8080
kotlin KonduitClient.kt

# Custom base URL:
kotlin KonduitClient.kt http://localhost:9090
```

### 3. Expected Output

```
╔══════════════════════════════════════════════════╗
║       Konduit Workflow Engine — Kotlin Client    ║
╚══════════════════════════════════════════════════╝

Base URL: http://localhost:8080

── Step 1: List available workflows ──
  Available workflows: [{"name":"npo-onboarding", ...}]

── Step 2: Trigger workflow execution ──
  Request: POST /executions
  Status: 201
  Execution ID: 550e8400-e29b-41d4-a716-446655440000

── Step 3: Poll for completion ──
  Poll #1: status=RUNNING
  Poll #2: status=RUNNING
  Poll #3: status=COMPLETED

✅ Execution completed successfully!

── Step 4: Read execution result ──
  { ... full execution JSON ... }

Done! 🎉
```

## Webhook Callback Handling

If you triggered the execution with a `callbackUrl`, Konduit will POST the execution result to that URL when the workflow reaches a terminal state (COMPLETED, FAILED, CANCELLED, TIMED_OUT).

Example trigger with callback:

```json
{
  "workflowName": "npo-onboarding",
  "input": { "orgName": "Acme" },
  "callbackUrl": "https://your-server.com/webhook/konduit"
}
```

The webhook payload:

```json
{
  "event": "execution.completed",
  "timestamp": "2026-02-21T12:00:00Z",
  "execution": { /* full ExecutionResponse */ }
}
```

## API Reference

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/v1/workflows` | List registered workflows |
| `POST` | `/api/v1/executions` | Trigger a new execution |
| `GET` | `/api/v1/executions/{id}` | Get execution by ID |
| `GET` | `/api/v1/executions` | List executions (paginated) |
| `GET` | `/api/v1/executions/{id}/tasks` | Get tasks for an execution |
| `GET` | `/api/v1/executions/{id}/timeline` | Get execution timeline |
| `POST` | `/api/v1/executions/{id}/cancel` | Cancel a running execution |
| `GET` | `/api/v1/dead-letters` | List dead letters |
| `GET` | `/api/v1/stats` | Get system statistics |
| `GET` | `/api/v1/workers` | List registered workers |

For interactive API documentation, visit: `http://localhost:8080/swagger-ui.html`

