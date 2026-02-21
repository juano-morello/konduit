# ADR-009: Workflow Versioning Strategy

## Status

Accepted

## Context

Konduit workflow definitions are registered at application startup via Spring beans that implement the DSL. The `WorkflowRegistry` collects all `WorkflowDefinition` beans, validates name+version uniqueness, and stores them in an in-memory map keyed by `"name:version"`. Definitions are also persisted to the `workflows` table (idempotent upsert) for auditability.

When a workflow execution is triggered, the execution entity records the `workflowName` and `workflowVersion` at creation time. The engine then looks up the workflow definition from the in-memory registry by name to execute steps.

This creates a potential consistency problem: if a workflow definition is modified or removed between application restarts, any RUNNING or PENDING executions that were created under the old definition may become **orphaned** — their recorded `workflowVersion` no longer matches any definition in the registry. These executions cannot complete successfully because the engine cannot find the step definitions and handlers needed to advance them.

### Scenarios Where Orphaning Occurs

1. **Version bump**: A workflow's version is incremented (e.g., v1 → v2) and the v1 definition bean is removed. Existing v1 executions lose their definition.
2. **Workflow removal**: A workflow bean is entirely removed from the codebase. All in-flight executions for that workflow are orphaned.
3. **Step graph change without version bump**: A workflow's steps are modified but the version number is not incremented. The persisted step graph in the `workflows` table is updated, but the execution's recorded version still matches — however, the runtime behavior has changed, which may cause unexpected failures.

## Decision

Adopt a **version-at-creation, validate-at-startup** strategy:

1. **Version is immutable on execution**: When an execution is created, `workflowVersion` is set from the current `WorkflowDefinition.version` and never changes. This provides an audit trail of which definition version was used.

2. **Startup validation**: A `WorkflowVersionValidator` component runs after `WorkflowRegistry` initialization (via `@EventListener(ApplicationReadyEvent::class)`). It queries all RUNNING and PENDING executions and checks each against the registry using `findByNameAndVersion(name, version)`. Orphaned executions are logged at WARN level.

3. **Non-blocking validation**: The validator never throws exceptions or blocks startup. Orphaned executions are a warning condition, not a fatal error. Operators can decide how to handle them (cancel, migrate, or wait for a fix deployment).

4. **Safe evolution guidelines**:
   - **Always increment the version** when changing a workflow's step graph, handlers, or retry policies.
   - **Keep old version beans alive** until all in-flight executions under that version have completed.
   - **Use the `workflowVersion` field** on `ExecutionEntity` to query which executions are running under a specific version before removing it.
   - **Never change step semantics** without a version bump — even if the step names remain the same.

## Consequences

### Gained

- **Visibility**: Operators are alerted at startup if any active executions reference workflow definitions that no longer exist. This prevents silent failures where executions hang indefinitely.
- **Audit trail**: The `workflowVersion` field on each execution provides a permanent record of which definition version was used, enabling debugging and compliance.
- **Non-disruptive**: The validation is purely observational — it does not modify execution state or prevent the application from starting. This is safe for rolling deployments where old and new versions may coexist briefly.
- **Simple implementation**: No schema changes, no new tables, no migration. The validator uses existing repository methods and the in-memory registry.

### Lost

- **No automatic remediation**: Orphaned executions are only logged, not automatically cancelled or migrated. Manual intervention is required.
- **No multi-version runtime**: The registry does not support running multiple versions of the same workflow simultaneously. Only the latest registered version is used for new executions. Supporting side-by-side versions would require routing logic based on `workflowVersion`.
- **Version discipline required**: Developers must remember to increment versions when changing workflow definitions. There is no compile-time or CI enforcement of this rule.

### Future Considerations

- **Automatic orphan cancellation**: A configuration flag could allow the validator to automatically cancel orphaned executions after logging, reducing manual toil.
- **Multi-version support**: The registry could be extended to keep multiple versions alive simultaneously, routing each execution to its recorded version. This would eliminate the orphan problem entirely but adds significant complexity.
- **CI version check**: A build-time check could compare workflow definitions against the previous release to detect step graph changes without version bumps.

