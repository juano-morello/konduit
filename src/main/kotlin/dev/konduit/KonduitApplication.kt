package dev.konduit

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(KonduitProperties::class)
class KonduitApplication

fun main(args: Array<String>) {
    runApplication<KonduitApplication>(*args)
}

