package dev.konduit.engine

import dev.konduit.dsl.WorkflowRegistry
import dev.konduit.persistence.entity.ExecutionStatus
import dev.konduit.persistence.repository.ExecutionRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Component

/**
 * Validates on startup that all active (RUNNING/PENDING) executions have
 * matching workflow definitions in the [WorkflowRegistry].
 *
 * Orphaned executions — those whose workflow name+version is no longer
 * registered — are logged at WARN level. This validator never throws
 * and never blocks application startup.
 *
 * @see WorkflowRegistry
 * @see <a href="docs/adr/009-workflow-versioning.md">ADR-009: Workflow Versioning Strategy</a>
 */
@Component
class WorkflowVersionValidator(
    private val executionRepository: ExecutionRepository,
    private val workflowRegistry: WorkflowRegistry
) {
    private val log = LoggerFactory.getLogger(WorkflowVersionValidator::class.java)

    /**
     * Runs after the application context is fully initialized and the
     * [WorkflowRegistry] has registered all workflow definitions.
     *
     * Queries all RUNNING and PENDING executions and checks each one
     * against the registry. Logs a WARNING for any execution whose
     * workflow name+version combination is not found.
     */
    @EventListener(ApplicationReadyEvent::class)
    fun validateOnStartup() {
        try {
            val activeExecutions = executionRepository.findByStatusIn(
                listOf(ExecutionStatus.RUNNING, ExecutionStatus.PENDING),
                Pageable.unpaged()
            )

            if (activeExecutions.isEmpty) {
                log.info("No active executions to validate against workflow registry")
                return
            }

            var orphanCount = 0
            for (execution in activeExecutions) {
                val workflow = workflowRegistry.findByNameAndVersion(
                    execution.workflowName,
                    execution.workflowVersion
                )
                if (workflow == null) {
                    orphanCount++
                    log.warn(
                        "Orphaned execution detected: id={}, workflow='{}' v{} — " +
                            "no matching definition in registry. " +
                            "This execution may not complete successfully.",
                        execution.id,
                        execution.workflowName,
                        execution.workflowVersion
                    )
                }
            }

            if (orphanCount > 0) {
                log.warn(
                    "Workflow version validation complete: {}/{} active execution(s) are orphaned " +
                        "(workflow definition not found in registry)",
                    orphanCount,
                    activeExecutions.totalElements
                )
            } else {
                log.info(
                    "Workflow version validation complete: all {} active execution(s) have matching " +
                        "workflow definitions in registry",
                    activeExecutions.totalElements
                )
            }
        } catch (e: Exception) {
            log.warn(
                "Workflow version validation failed (non-fatal): {}",
                e.message,
                e
            )
        }
    }
}

