# ADR-005: Lazy Evaluation for Conditional Branching

## Status

Accepted

## Context

Konduit supports conditional branching where the output of a previous step determines which execution path to follow. The system needs to decide when to create tasks for branch steps:

1. **Eager creation**: Create tasks for all possible branches upfront, then cancel unmatched branches at evaluation time
2. **Lazy creation**: Evaluate the branch condition first, then create tasks only for the matched branch

## Decision

Evaluate branch conditions at dispatch time and create tasks **only** for the matched branch. When the execution engine encounters a `BranchBlock`, it:

1. Retrieves the previous step's output
2. Converts it to a string for matching against `on()` conditions
3. Finds the first matching branch (or falls back to `otherwise`)
4. Creates tasks only for the matched branch's steps
5. If no match and no `otherwise` branch, fails the execution

Unmatched branches never have tasks created — they don't exist in the database.

```kotlin
branch("risk-level") {
    on("LOW") {
        step("fast-track") { handler { ctx -> approve(ctx.input) } }
    }
    on("HIGH") {
        step("deep-review") { handler { ctx -> review(ctx.input) } }
        step("escalate") { handler { ctx -> escalate(ctx.input) } }
    }
    otherwise {
        step("manual") { handler { ctx -> manualReview(ctx.input) } }
    }
}
```

If the previous step returns `"LOW"`, only the `fast-track` task is created. The `deep-review`, `escalate`, and `manual` tasks are never instantiated.

## Rationale

- **No wasted work**: Unmatched branches consume zero resources — no task rows, no worker capacity, no cleanup.
- **Simpler state management**: No need to track "cancelled-before-execution" tasks or distinguish between "cancelled by branch" and "cancelled by user."
- **Cleaner execution timeline**: The execution history shows only the path that was actually taken, making debugging straightforward.
- **Consistent with at-least-once semantics**: Fewer tasks created means fewer tasks that could potentially execute unexpectedly.

## Trade-offs

**Gained:**
- Minimal resource usage (only matched branch tasks exist)
- Clean execution history showing only the actual path taken
- Simpler task lifecycle (no "pre-cancelled" state)
- Straightforward debugging and observability

**Lost:**
- Branch evaluation happens inside the engine (not pure data) — the engine must have access to handler outputs at dispatch time
- Cannot preview all possible execution paths by looking at task records alone (must inspect the workflow definition)
- Branch condition matching is string-based (`toString()` comparison), which may be limiting for complex conditions
- If branch evaluation fails (e.g., null output), the execution fails rather than falling through to a default

