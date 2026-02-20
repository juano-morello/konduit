# ADR-002: Type-Safe Kotlin DSL for Workflow Definitions

## Status

Accepted

## Context

Konduit needs a user-friendly API for defining workflows — the sequence of steps, parallel blocks, conditional branches, retry policies, and handler functions. Common approaches include YAML/JSON configuration files, annotation-based definitions, or programmatic builders.

The target audience is Kotlin/JVM developers who will define workflows as part of their application code.

## Decision

Use a type-safe Kotlin DSL with builder functions (`workflow {}`, `step {}`, `parallel {}`, `branch {}`) for workflow definitions. Workflows are defined as Spring `@Bean` methods or `@Component` classes that produce `WorkflowDefinition` objects, which are automatically collected by the `WorkflowRegistry` at startup.

```kotlin
@Bean
fun myWorkflow(): WorkflowDefinition = workflow("my-workflow") {
    step("validate") {
        handler { ctx -> validateInput(ctx.input) }
        retryPolicy {
            maxAttempts(3)
            backoff(BackoffStrategy.EXPONENTIAL)
            jitter(true)
        }
    }
    parallel {
        step("enrich-a") { handler { ctx -> enrichA(ctx.input) } }
        step("enrich-b") { handler { ctx -> enrichB(ctx.input) } }
    }
    step("finalize") { handler { ctx -> finalize(ctx.input) } }
}
```

The DSL produces two outputs:
1. An in-memory `WorkflowDefinition` with handler references (for runtime execution)
2. A serializable step graph (stored as JSONB in the `workflows` table)

## Rationale

- **Compile-time safety**: Invalid workflow structures (missing handlers, duplicate step names, empty parallel blocks) are caught at compile time or during bean initialization — not at runtime.
- **IDE support**: Full autocomplete, type checking, and refactoring support in IntelliJ IDEA.
- **Kotlin-native**: Leverages Kotlin's `@DslMarker`, extension functions, and lambda-with-receiver patterns for a natural, readable API.
- **Dependency injection**: Workflow definitions are Spring beans, so handlers can reference injected services via closure capture.
- **No config parsing**: No YAML/JSON schema to maintain, no deserialization errors, no config file discovery.

## Trade-offs

**Gained:**
- Compile-time validation of workflow structure
- Full IDE autocomplete and navigation
- Type-safe retry policy and timeout configuration
- Natural Kotlin syntax that reads like a specification

**Lost:**
- Requires Kotlin knowledge (not accessible to non-developers)
- Workflow definitions are not serializable for external storage or dynamic modification (the handler lambdas cannot be serialized)
- Cannot define workflows at runtime without recompilation
- Tighter coupling between workflow definition and application code

