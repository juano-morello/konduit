package dev.konduit.config

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.composite.CompositeMeterRegistry
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Provides a no-op [MeterRegistry] fallback when no real registry (e.g. Prometheus)
 * is configured. An empty [CompositeMeterRegistry] silently discards all metrics,
 * allowing [dev.konduit.observability.MetricsService] to use a non-null registry
 * unconditionally.
 */
@Configuration
class MetricsConfig {

    @Bean
    @ConditionalOnMissingBean(MeterRegistry::class)
    fun noOpMeterRegistry(): MeterRegistry = CompositeMeterRegistry()
}

