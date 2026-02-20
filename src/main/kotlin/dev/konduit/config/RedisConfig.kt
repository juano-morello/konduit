package dev.konduit.config

import dev.konduit.KonduitProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import org.springframework.data.redis.serializer.StringRedisSerializer

/**
 * Redis configuration for Konduit coordination layer.
 *
 * Provides RedisTemplate, pub/sub listener container, and health indicator.
 * Redis is enabled by default but can be disabled via `konduit.redis.enabled=false`.
 * When disabled, Spring's Redis auto-configuration is not used and NoOp
 * implementations are activated for signaling and leader election.
 */
@Configuration
@ConditionalOnProperty(
    name = ["konduit.redis.enabled"],
    havingValue = "true",
    matchIfMissing = true
)
class RedisConfig {

    private val log = LoggerFactory.getLogger(RedisConfig::class.java)

    @Bean
    fun konduitRedisTemplate(connectionFactory: RedisConnectionFactory): RedisTemplate<String, String> {
        val template = RedisTemplate<String, String>()
        template.connectionFactory = connectionFactory
        template.keySerializer = StringRedisSerializer()
        template.valueSerializer = StringRedisSerializer()
        template.hashKeySerializer = StringRedisSerializer()
        template.hashValueSerializer = StringRedisSerializer()
        template.afterPropertiesSet()
        log.info("Konduit RedisTemplate configured")
        return template
    }

    @Bean
    @ConditionalOnBean(RedisTemplate::class)
    fun redisMessageListenerContainer(
        connectionFactory: RedisConnectionFactory
    ): RedisMessageListenerContainer {
        val container = RedisMessageListenerContainer()
        container.setConnectionFactory(connectionFactory)
        log.info("Redis message listener container configured")
        return container
    }

    @Bean
    @ConditionalOnBean(RedisTemplate::class)
    fun redisHealthIndicator(
        redisTemplate: RedisTemplate<String, String>
    ): HealthIndicator {
        return HealthIndicator {
            try {
                redisTemplate.connectionFactory?.connection?.ping()
                Health.up().withDetail("redis", "available").build()
            } catch (e: Exception) {
                log.warn("Redis health check failed: {}", e.message)
                Health.down()
                    .withDetail("redis", "unavailable")
                    .withDetail("error", e.message ?: "unknown")
                    .build()
            }
        }
    }
}

