package dev.konduit.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * OpenAPI/SpringDoc configuration for interactive API documentation.
 * Swagger UI is served at /swagger-ui.html and OpenAPI JSON at /v3/api-docs.
 */
@Configuration
class OpenApiConfig {

    @Bean
    fun openApiDefinition(): OpenAPI = OpenAPI()
        .info(
            Info()
                .title("Konduit Workflow Engine API")
                .version("0.1.0")
                .description("Durable, stateful workflow orchestration engine with sequential, parallel, and branching execution support")
        )
}

