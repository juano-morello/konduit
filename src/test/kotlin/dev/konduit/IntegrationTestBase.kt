package dev.konduit

import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer

/**
 * Base class for integration tests using Testcontainers PostgreSQL.
 *
 * Uses a singleton container pattern so all test classes share the same
 * PostgreSQL instance (started once, reused across all tests).
 * Flyway runs migrations automatically on first connection.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
abstract class IntegrationTestBase {

    companion object {
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("konduit_test")
            .withUsername("test")
            .withPassword("test")
            .apply { start() }

        @DynamicPropertySource
        @JvmStatic
        fun configureProperties(registry: DynamicPropertyRegistry) {
            // stringtype=unspecified is required so the JDBC driver doesn't send
            // enum values as VARCHAR — PostgreSQL custom enum columns reject that.
            registry.add("spring.datasource.url") { "${postgres.jdbcUrl}&stringtype=unspecified" }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
        }
    }
}

