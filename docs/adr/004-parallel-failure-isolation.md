# ADR-004: Parallel Step Failure Isolation

## Status

Accepted

## Context

Konduit supports parallel execution blocks where multiple steps run concurrently (fan-out). When one parallel step fails (after exhausting retries), the system must decide what happens to the other running parallel steps:

1. **Cancel siblings**: Immediately cancel all other parallel steps when one fails
2. **Isolate failures**: Let all parallel steps run to completion regardless of individual failures

This decision affects resource usage, execution latency on failure, and data preservation.

## Decision

Failed parallel steps do **not** cancel running siblings. Each parallel step is treated as an independent work unit. The parallel block waits for all steps to reach a terminal state (COMPLETED or DEAD_LETTERED) before the execution engine evaluates the overall result.

If any parallel step is dead-lettered, the entire execution is marked as FAILED after all siblings complete. The outputs of successful parallel steps are preserved and available for inspection.

## Rationale

- **Independent work units**: Parallel steps are semantically independent — they don't depend on each other's output. Canceling a successful step discards valid, potentially expensive work.
- **Simpler state management**: No need for a cancellation protocol, no race conditions between "cancel" signals and "complete" signals, no partial rollback logic.
- **Observability**: All parallel steps run to completion, so operators can see the full picture — which steps succeeded and which failed — without re-running the execution.
- **Idempotency alignment**: Since the system guarantees at-least-once execution, canceling a step mid-flight doesn't guarantee it won't have side effects. Letting it complete is safer.

## Trade-offs

**Gained:**
- No valid work is discarded on partial failure
- Simpler execution engine logic (no cancellation protocol)
- Full observability of all parallel step outcomes
- No race conditions between cancel and complete signals

**Lost:**
- Execution may take longer on failure (waits for all steps to finish, even when the outcome is already FAILED)
- Uses more resources on failure (all parallel steps continue consuming worker threads and downstream service capacity)
- No "fail-fast" option for cost-sensitive parallel operations
- Dead-lettered step outputs are not available to post-parallel steps (only successful outputs are passed forward)

