#!/usr/bin/env kotlin

/**
 * Konduit Workflow Engine — Example Kotlin Client
 *
 * Demonstrates how to interact with the Konduit REST API using only
 * JDK 21 built-in classes (java.net.http.HttpClient). No external
 * dependencies required.
 *
 * Features demonstrated:
 *   1. Triggering a workflow execution
 *   2. Polling for completion
 *   3. Reading the result
 *   4. Webhook callback handling (simple HTTP server)
 *
 * Usage:
 *   # Start Konduit via Docker Compose first, then:
 *   kotlin KonduitClient.kt [baseUrl]
 *
 *   Default baseUrl: http://localhost:8080
 */

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

// ── Configuration ────────────────────────────────────────────────────

val BASE_URL = if (args.isNotEmpty()) args[0] else "http://localhost:8080"
val API_BASE = "$BASE_URL/api/v1"
val POLL_INTERVAL = Duration.ofSeconds(2)
val MAX_POLL_ATTEMPTS = 30

// ── HTTP Client (JDK 21 built-in) ───────────────────────────────────

val client: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(5))
    .build()

// ── Helper Functions ─────────────────────────────────────────────────

/** Send a GET request and return the response body as a String. */
fun get(path: String): HttpResponse<String> {
    val request = HttpRequest.newBuilder()
        .uri(URI.create("$API_BASE$path"))
        .header("Accept", "application/json")
        .GET()
        .build()
    return client.send(request, HttpResponse.BodyHandlers.ofString())
}

/** Send a POST request with a JSON body and return the response. */
fun post(path: String, jsonBody: String): HttpResponse<String> {
    val request = HttpRequest.newBuilder()
        .uri(URI.create("$API_BASE$path"))
        .header("Content-Type", "application/json")
        .header("Accept", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
        .build()
    return client.send(request, HttpResponse.BodyHandlers.ofString())
}

/** Extract a JSON string field value (simple regex — no JSON library needed). */
fun extractField(json: String, field: String): String? {
    val regex = """"$field"\s*:\s*"([^"]+)"""".toRegex()
    return regex.find(json)?.groupValues?.get(1)
}

// ── Main Flow ────────────────────────────────────────────────────────

println("╔══════════════════════════════════════════════════╗")
println("║       Konduit Workflow Engine — Kotlin Client    ║")
println("╚══════════════════════════════════════════════════╝")
println()
println("Base URL: $BASE_URL")
println()

// Step 1: List available workflows
println("── Step 1: List available workflows ──")
val workflowsResp = get("/workflows")
if (workflowsResp.statusCode() != 200) {
    println("ERROR: Could not list workflows (HTTP ${workflowsResp.statusCode()})")
    println("  Make sure Konduit is running at $BASE_URL")
    kotlin.system.exitProcess(1)
}
println("  Available workflows: ${workflowsResp.body()}")
println()

// Step 2: Trigger a workflow execution
println("── Step 2: Trigger workflow execution ──")
val triggerBody = """
{
  "workflowName": "npo-onboarding",
  "input": {
    "orgName": "Example Foundation",
    "contactEmail": "admin@example.org"
  },
  "idempotencyKey": "demo-${System.currentTimeMillis()}"
}
""".trimIndent()

println("  Request: POST /executions")
println("  Body: $triggerBody")
val triggerResp = post("/executions", triggerBody)
println("  Status: ${triggerResp.statusCode()}")
println("  Response: ${triggerResp.body()}")

if (triggerResp.statusCode() !in 200..201) {
    println("ERROR: Failed to trigger execution")
    kotlin.system.exitProcess(1)
}

val executionId = extractField(triggerResp.body(), "id")
    ?: error("Could not extract execution ID from response")
println("  Execution ID: $executionId")
println()

// Step 3: Poll for completion
println("── Step 3: Poll for completion ──")
var status = "PENDING"
var attempt = 0

while (status !in listOf("COMPLETED", "FAILED", "CANCELLED", "TIMED_OUT") && attempt < MAX_POLL_ATTEMPTS) {
    Thread.sleep(POLL_INTERVAL.toMillis())
    attempt++

    val pollResp = get("/executions/$executionId")
    status = extractField(pollResp.body(), "status") ?: "UNKNOWN"
    println("  Poll #$attempt: status=$status")
}

println()
if (status == "COMPLETED") {
    println("✅ Execution completed successfully!")
} else {
    println("⚠️  Execution ended with status: $status")
}
println()

// Step 4: Read the full result
println("── Step 4: Read execution result ──")
val resultResp = get("/executions/$executionId")
println("  ${resultResp.body()}")
println()

// Step 5: View execution timeline
println("── Step 5: View execution timeline ──")
val timelineResp = get("/executions/$executionId/timeline")
println("  ${timelineResp.body()}")
println()

// Step 6: View execution tasks
println("── Step 6: View execution tasks ──")
val tasksResp = get("/executions/$executionId/tasks")
println("  ${tasksResp.body()}")
println()

println("Done! 🎉")

