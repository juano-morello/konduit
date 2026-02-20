package dev.konduit.dsl

import dev.konduit.persistence.entity.WorkflowEntity
import dev.konduit.persistence.repository.WorkflowRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Central registry for workflow definitions.
 *
 * At startup, collects all [WorkflowDefinition] beans from the Spring context,
 * validates name+version uniqueness, persists them to the workflows table
 * (idempotent upsert), and provides runtime lookup by name.
 *
 * The registry maintains an in-memory map of definitions (including handler references)
 * while the database stores the serializable step graph (without handlers).
 */
@Component
class WorkflowRegistry(
    private val workflowDefinitions: List<WorkflowDefinition>,
    private val workflowRepository: WorkflowRepository
) {
    private val log = LoggerFactory.getLogger(WorkflowRegistry::class.java)

    /** In-memory registry keyed by "name:version" */
    private val registry = mutableMapOf<String, WorkflowDefinition>()

    /**
     * Called after the application context is fully initialized.
     * Validates and registers all workflow definitions.
     */
    @EventListener(ApplicationReadyEvent::class)
    fun initialize() {
        log.info("Initializing workflow registry with {} definition(s)", workflowDefinitions.size)

        validateUniqueness()

        for (definition in workflowDefinitions) {
            register(definition)
        }

        log.info("Workflow registry initialized: {}", registry.keys)
    }

    /**
     * Validate that no two definitions share the same name+version.
     * @throws IllegalStateException if duplicates are found.
     */
    private fun validateUniqueness() {
        val duplicates = workflowDefinitions
            .groupBy { "${it.name}:${it.version}" }
            .filter { it.value.size > 1 }
            .keys

        if (duplicates.isNotEmpty()) {
            throw IllegalStateException(
                "Duplicate workflow definitions found: $duplicates. " +
                    "Each name+version combination must be unique."
            )
        }
    }

    /**
     * Register a single workflow definition: store in memory and persist to DB.
     */
    private fun register(definition: WorkflowDefinition) {
        val key = "${definition.name}:${definition.version}"
        registry[key] = definition

        persistToDatabase(definition)

        log.info(
            "Registered workflow '{}' v{} with {} element(s), {} step(s)",
            definition.name, definition.version, definition.elements.size, definition.steps.size
        )
    }

    /**
     * Idempotent upsert of the workflow definition to the database.
     * If a row with the same name+version exists, updates the step definitions.
     * Otherwise, inserts a new row.
     */
    private fun persistToDatabase(definition: WorkflowDefinition) {
        val existing = workflowRepository.findByNameAndVersion(
            definition.name, definition.version
        )

        // Wrap the serializable steps in a map for JSONB storage
        val stepDefinitionsMap: Map<String, Any> = mapOf(
            "steps" to definition.toSerializableSteps()
        )

        if (existing != null) {
            existing.description = definition.description
            existing.stepDefinitions = stepDefinitionsMap
            workflowRepository.save(existing)
            log.debug("Updated workflow '{}' v{} in database", definition.name, definition.version)
        } else {
            val entity = WorkflowEntity(
                name = definition.name,
                version = definition.version,
                description = definition.description,
                stepDefinitions = stepDefinitionsMap
            )
            workflowRepository.save(entity)
            log.debug("Inserted workflow '{}' v{} into database", definition.name, definition.version)
        }
    }

    /**
     * Look up a workflow definition by name (latest version).
     * @return The workflow definition, or null if not found.
     */
    fun findByName(name: String): WorkflowDefinition? {
        return registry.entries
            .filter { it.key.startsWith("$name:") }
            .maxByOrNull { it.value.version }
            ?.value
    }

    /**
     * Look up a workflow definition by name and version.
     * @return The workflow definition, or null if not found.
     */
    fun findByNameAndVersion(name: String, version: Int): WorkflowDefinition? {
        return registry["$name:$version"]
    }

    /**
     * Get a workflow definition by name, throwing if not found.
     * @throws IllegalArgumentException if the workflow is not registered.
     */
    fun getByName(name: String): WorkflowDefinition {
        return findByName(name)
            ?: throw IllegalArgumentException(
                "Workflow '$name' not found in registry. Available: ${getRegisteredNames()}"
            )
    }

    /**
     * Get all registered workflow names (without version suffix).
     */
    fun getRegisteredNames(): Set<String> {
        return registry.values.map { it.name }.toSet()
    }

    /**
     * Get all registered workflow definitions.
     */
    fun getAll(): List<WorkflowDefinition> {
        return registry.values.toList()
    }
}

