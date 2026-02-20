# ADR-003: Configurable Backoff with Jitter for Retries

## Status

Accepted

## Context

When workflow steps fail (transient errors, downstream timeouts, rate limits), the system needs to retry them. Naive retry strategies — fixed delay or simple exponential backoff — can cause thundering herd problems when many tasks fail simultaneously and all retry at the same time, overwhelming the downstream service again.

## Decision

Implement configurable retry with three backoff strategies and optional jitter:

- **FIXED**: Constant delay between retries (`delay = baseDelay`)
- **LINEAR**: Linearly increasing delay (`delay = baseDelay × attempt`)
- **EXPONENTIAL**: Exponentially increasing delay (`delay = baseDelay × 2^(attempt-1)`)

All strategies support:
- `maxAttempts`: Total attempts including the initial execution (minimum 1)
- `baseDelayMs`: Base delay used in the backoff formula
- `maxDelayMs`: Upper bound cap on computed delay
- `jitter`: When enabled, applies random jitter (0–100% of computed delay) to desynchronize retries

Jitter is applied by multiplying the computed delay by a random factor in `[0.5, 1.5)`, then clamping to `maxDelayMs`. This spreads retry attempts across a time window rather than concentrating them at exact intervals.

Each step can configure its own retry policy via the DSL:

```kotlin
step("call-external-api") {
    handler { ctx -> callApi(ctx.input) }
    retryPolicy {
        maxAttempts(5)
        backoff(BackoffStrategy.EXPONENTIAL)
        baseDelay(1000)
        maxDelay(60_000)
        jitter(true)
    }
}
```

## Rationale

- **Thundering herd prevention**: Jitter ensures that retries from concurrent failures are spread across time, reducing peak load on downstream services.
- **Per-step configurability**: Different steps may call different services with different failure characteristics. A database call might use fixed backoff with 3 attempts, while an external API call might use exponential backoff with jitter and 5 attempts.
- **Predictable upper bound**: `maxDelayMs` ensures retries don't wait indefinitely, keeping execution latency bounded.
- **Industry standard**: Exponential backoff with jitter is the recommended retry strategy by AWS, Google Cloud, and most distributed systems literature.

## Trade-offs

**Gained:**
- Protection against synchronized retry storms
- Fine-grained control over retry behavior per step
- Bounded retry delays via `maxDelayMs`
- Well-understood, industry-standard approach

**Lost:**
- Added complexity in retry delay calculation (three strategies + jitter + clamping)
- Harder to predict exact retry timing (especially with jitter enabled)
- More configuration surface area for users to understand
- Jitter makes debugging timing-sensitive issues slightly harder (non-deterministic delays)

