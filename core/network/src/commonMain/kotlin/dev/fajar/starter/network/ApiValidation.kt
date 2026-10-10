package dev.fajar.starter.network

import dev.fajar.starter.common.result.*
import io.ktor.client.plugins.ResponseException
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Optional API contract: {"errors":{"email":"invalid","password":"required"}}. */
suspend fun readApiFailure(exception: Exception): Failure {
    val fallback = mapHttpFailure(exception)
    if (exception !is ResponseException || exception.response.status.value !in setOf(400, 422))
        return fallback
    val fields =
        try {
            // Ktor already caches error responses. Do not include their content in diagnostics.
            val body = exception.response.bodyAsText()
            if (body.length > 16_384) return fallback
            parseFieldViolations(body)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyMap()
        }
    return fallback.copy(violations = fields)
}

fun parseFieldViolations(json: String): Map<String, ValidationIssue> {
    val errors =
        (Json.parseToJsonElement(json) as? JsonObject)?.get("errors") as? JsonObject
            ?: return emptyMap()
    return errors.entries
        .take(32)
        .mapNotNull { (field, value) ->
            if (!field.matches(Regex("[a-zA-Z][a-zA-Z0-9_]{0,39}"))) return@mapNotNull null
            val issue =
                when ((value as? JsonPrimitive)?.content) {
                    "required" -> ValidationIssue.Required
                    "invalid" -> ValidationIssue.Invalid
                    "already_exists" -> ValidationIssue.AlreadyExists
                    else -> ValidationIssue.Rejected
                }
            field to issue
        }
        .toMap()
}
