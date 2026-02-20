package dev.konduit.dsl

/**
 * DSL marker annotation to prevent scope leaking in nested Kotlin DSL builders.
 * Ensures that inner builder blocks cannot accidentally access outer builder methods.
 */
@DslMarker
annotation class KonduitDsl

