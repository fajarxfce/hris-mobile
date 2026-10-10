package dev.fajar.starter.network

import dev.fajar.starter.common.result.*
import io.ktor.client.plugins.ResponseException
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Reads RFC 9457 extensions or the legacy errors map without exposing server prose. */
suspend fun readApiFailure(exception: Exception): Failure {
    val fallback = mapHttpFailure(exception)
    if (exception is ApiResponseException) {
        return parseApiFailure(
            exception.problemBody,
            fallback,
            exception.retryAfter,
            exception.requestId,
        )
    }
    if (exception !is ResponseException) return fallback
    // Compatibility for clients outside requestJson. Ktor caches these exception responses;
    // first-party API services use requestJson to bound acquisition before that cache exists.
    val body =
        try {
            exception.response.bodyAsText().takeIf { it.length <= 16_384 }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    return parseApiFailure(
        body,
        fallback,
        exception.response.headers[HttpHeaders.RetryAfter],
        exception.response.headers["X-Request-ID"],
    )
}

fun parseApiFailure(
    body: String?,
    fallback: Failure,
    retryAfter: String? = null,
    requestId: String? = null,
): Failure {
    val document =
        try {
            body
                ?.takeIf { it.length <= 16_384 && hasBoundedJsonDepth(it) }
                ?.let { Json.parseToJsonElement(it) as? JsonObject }
        } catch (_: Exception) {
            null
        }
    val fields = document?.get("fields") as? JsonObject
    val fieldCodes =
        fields
            .orEmpty()
            .entries
            .take(32)
            .mapNotNull { (field, value) ->
                val code = (value as? JsonPrimitive)?.contentOrNull
                if (validApiField(field) && code?.matches(API_CODE) == true) field to code else null
            }
            .toMap()
    val violations =
        if (fields != null)
            fields.entries
                .take(32)
                .mapNotNull { (field, value) ->
                    if (validApiField(field))
                        field to apiValidationIssue((value as? JsonPrimitive)?.contentOrNull)
                    else null
                }
                .toMap()
        else parseLegacyViolations(document?.get("errors") as? JsonObject)
    val parameters =
        (document?.get("parameters") as? JsonObject)
            .orEmpty()
            .entries
            .take(32)
            .mapNotNull { (key, value) ->
                val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                if (
                    key.matches(API_PARAMETER) &&
                        text != null &&
                        text.length <= 256 &&
                        text.none { it < ' ' }
                )
                    key to text
                else null
            }
            .toMap()
    val code =
        (document?.get("code") as? JsonPrimitive)?.contentOrNull?.takeIf { it.matches(API_CODE) }
    val reference = (document?.get("correlationId") as? JsonPrimitive)?.contentOrNull ?: requestId
    val delay =
        retryAfter?.toLongOrNull()
            ?: (document?.get("retryAfterSeconds") as? JsonPrimitive)?.longOrNull
    return fallback.copy(
        code = code,
        violations = violations,
        fieldCodes = fieldCodes,
        parameters = parameters,
        correlationId = reference?.takeIf { it.matches(API_REFERENCE) },
        retryAfterSeconds = delay?.takeIf { it in 0..86_400 },
    )
}

fun parseFieldViolations(json: String): Map<String, ValidationIssue> {
    require(json.length <= 16_384 && hasBoundedJsonDepth(json)) { "Invalid validation response." }
    return parseLegacyViolations(
        (Json.parseToJsonElement(json) as? JsonObject)?.get("errors") as? JsonObject
    )
}

private fun parseLegacyViolations(errors: JsonObject?): Map<String, ValidationIssue> =
    errors
        .orEmpty()
        .entries
        .take(32)
        .mapNotNull { (field, value) ->
            if (validApiField(field))
                field to apiValidationIssue((value as? JsonPrimitive)?.contentOrNull)
            else null
        }
        .toMap()

private fun apiValidationIssue(code: String?): ValidationIssue =
    when (code) {
        "required" -> ValidationIssue.Required
        "invalid" -> ValidationIssue.Invalid
        "already_exists" -> ValidationIssue.AlreadyExists
        else -> ValidationIssue.Rejected
    }

private fun validApiField(field: String): Boolean = field.matches(API_FIELD) && ".." !in field

private val API_CODE = Regex("[a-z][a-z0-9_]{0,79}")
private val API_PARAMETER = Regex("[a-zA-Z][a-zA-Z0-9_]{0,47}")
private val API_FIELD = Regex("[a-zA-Z][a-zA-Z0-9_.\\[\\]]{0,159}")
private val API_REFERENCE =
    Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
