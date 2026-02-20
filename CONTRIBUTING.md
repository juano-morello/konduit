# Contributing to Konduit

Thank you for your interest in contributing to Konduit! This guide covers the development setup, testing workflow, and conventions used in this project.

## Prerequisites

- **Java 21** (LTS) — [Eclipse Temurin](https://adoptium.net/) recommended
- **Docker** and **Docker Compose** — required for integration tests (Testcontainers) and local stack
- **Git** — for version control

## Development Setup

1. **Clone the repository:**
   ```bash
   git clone <repository-url>
   cd konduit
   ```

2. **Start infrastructure services:**
   ```bash
   docker-compose up -d postgres redis
   ```

3. **Run the application locally:**
   ```bash
   ./gradlew bootRun
   ```
   The API will be available at `http://localhost:8080`.

4. **Verify health:**
   ```bash
   curl http://localhost:8080/actuator/health
   ```

## Running Tests

The test suite uses [Testcontainers](https://testcontainers.com/) to spin up PostgreSQL automatically. Docker must be running.

```bash
# Run the full test suite (unit + integration)
./gradlew test

# Run with verbose output
./gradlew test --info

# Run a specific test class
./gradlew test --tests "dev.konduit.engine.ExecutionEngineIntegrationTest"

# Full build (compile + test + jar)
./gradlew build
```

No manual database setup is required — tests are fully self-contained.

## Project Structure

```
src/
├── main/kotlin/dev/konduit/
│   ├── api/          # REST controllers, DTOs, error handling
│   ├── config/       # Redis configuration, health indicators
│   ├── coordination/ # Redis pub/sub signaling, leader election
│   ├── dsl/          # Kotlin DSL builders, workflow definitions
│   ├── engine/       # Execution state machine, task dispatching
│   ├── observability/# Micrometer metrics, correlation filter
│   ├── persistence/  # JPA entities, Spring Data repositories
│   ├── queue/        # Task queue (SKIP LOCKED), dead letter queue
│   ├── retry/        # Retry policies, backoff calculation
│   └── worker/       # Task worker, heartbeat, registry
└── test/kotlin/dev/konduit/
    └── ...           # Mirrors main structure
```

## Commit Message Format

Use [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>(<scope>): <short summary>

<optional body>
```

**Types:**
- `feat` — New feature or capability
- `fix` — Bug fix
- `docs` — Documentation changes only
- `test` — Adding or updating tests
- `refactor` — Code change that neither fixes a bug nor adds a feature
- `chore` — Build, CI, or tooling changes

**Scopes** (optional): `api`, `engine`, `queue`, `worker`, `dsl`, `coordination`, `observability`, `persistence`, `config`

**Examples:**
```
feat(engine): add parallel step fan-out/fan-in
fix(queue): prevent duplicate task acquisition under high concurrency
docs: add ADR-008 for PostgreSQL enum mapping
test(worker): add graceful shutdown integration test
```

## Pull Request Process

1. **Create a feature branch** from `main`:
   ```bash
   git checkout -b feat/my-feature
   ```

2. **Make your changes** following existing code patterns and conventions.

3. **Run the full test suite** and ensure all tests pass:
   ```bash
   ./gradlew build
   ```

4. **Write or update tests** for any new or changed behavior.

5. **Submit a pull request** with:
   - A clear title following the commit message format
   - A description of what changed and why
   - Reference to any related issues or ADRs

## Architecture Decision Records

Significant design decisions are documented as ADRs in `docs/adr/`. If your change involves a non-trivial architectural choice, please add a new ADR following the existing format:

```
# ADR-NNN: Title

## Status
Accepted | Proposed | Deprecated

## Context
Why is this decision needed?

## Decision
What was decided and key implementation details.

## Rationale
Why this approach over alternatives.

## Trade-offs
What was gained and what was lost.
```

## Code Style

- **Language:** Kotlin 2.0 with Spring Boot 3.4 idioms
- **Logging:** Use `LoggerFactory.getLogger(ClassName::class.java)` with the variable name `log`
- **Null safety:** Avoid `!!` in production code; use safe calls, `let`, or `require`/`check`
- **Testing:** JUnit 5 + MockK for unit tests; `@SpringBootTest` + Testcontainers for integration tests

