# ADR-008: PostgreSQL Custom Enum Type Mapping

## Status

Accepted

## Context

The Konduit database schema defines custom PostgreSQL enum types (`task_status`, `worker_status`, `execution_status`, `step_type`) via `CREATE TYPE ... AS ENUM` in Flyway migrations. These provide type safety at the database level — PostgreSQL rejects any value not in the enum definition.

The standard JPA approach for mapping Java/Kotlin enums to database columns is `@Enumerated(EnumType.STRING)`, which sends enum values as JDBC `varchar` parameters. However, PostgreSQL does **not** implicitly cast `varchar` to custom enum types. This causes runtime errors:

```
ERROR: column "status" is of type worker_status but expression is of type character varying
  Hint: You will need to rewrite or cast the expression.
```

This affects all INSERT and parameterized WHERE clauses on enum columns. The mismatch is not caught by unit tests that mock repositories, and only surfaces when running against a real PostgreSQL instance with custom enum types (e.g., in Docker).

### Affected Fields

| Entity | Field | DB Column Type |
|--------|-------|---------------|
| `TaskEntity` | `status` | `task_status` |
| `TaskEntity` | `stepType` | `step_type` |
| `WorkerEntity` | `status` | `worker_status` |
| `ExecutionEntity` | `status` | `execution_status` |

## Decision

Replace `@Enumerated(EnumType.STRING)` with `@JdbcTypeCode(SqlTypes.NAMED_ENUM)` on all entity fields mapped to PostgreSQL custom enum columns. This annotation, available in Hibernate 6.2+, tells Hibernate to send enum values as PostgreSQL native enum types (via `PGobject` with the correct type OID) instead of `varchar`.

```kotlin
// Before (broken with PG custom enums):
@Enumerated(EnumType.STRING)
@Column(nullable = false)
var status: TaskStatus = TaskStatus.PENDING

// After (correct PG enum mapping):
@JdbcTypeCode(SqlTypes.NAMED_ENUM)
@Column(nullable = false, columnDefinition = "task_status")
var status: TaskStatus = TaskStatus.PENDING
```

The `columnDefinition` attribute is included to make the mapping explicit in DDL generation, though Flyway manages the actual schema.

Note: The `BackoffStrategy` enum on `TaskEntity.backoffStrategy` is stored as `VARCHAR(50)` (not a custom PG enum type), so it retains `@Enumerated(EnumType.STRING)`. Only columns backed by `CREATE TYPE ... AS ENUM` require `@JdbcTypeCode(SqlTypes.NAMED_ENUM)`.

## Rationale

- **Hibernate 6.2+ native support**: The project uses Hibernate 6.6.8 (via Spring Boot 3.4), which fully supports `SqlTypes.NAMED_ENUM` for PostgreSQL. No custom `UserType` or converter is needed.
- **Minimal change surface**: Only the annotation on 4 entity fields changes. No schema migration, no query changes, no application logic changes.
- **Preserves database-level type safety**: Custom PG enums enforce valid values at the database layer, catching bugs that `varchar` columns would silently accept.
- **Alternative considered — remove PG enums**: Converting columns to `VARCHAR` would also fix the mismatch but sacrifices database-level type safety and requires a schema migration to drop and recreate columns.
- **Alternative considered — CAST in queries**: Adding `CAST(? AS task_status)` to every native query is fragile and doesn't fix Spring Data derived query methods.

## Trade-offs

**Gained:**
- Correct enum parameter binding for all CRUD operations and derived query methods
- Database-level type safety preserved (invalid enum values rejected by PostgreSQL)
- Clean, annotation-based solution with no custom code
- Application starts and runs correctly in Docker against real PostgreSQL

**Lost:**
- Hibernate version lock-in: `SqlTypes.NAMED_ENUM` requires Hibernate 6.2+ (acceptable — Spring Boot 3.x ships with Hibernate 6.x)
- Slightly less portable: the mapping is PostgreSQL-specific. Switching to MySQL or H2 would require reverting to `@Enumerated(EnumType.STRING)` (acceptable — PostgreSQL is the only supported database)
- Developers must remember to use `@JdbcTypeCode(SqlTypes.NAMED_ENUM)` for any new entity fields mapped to PG custom enums

