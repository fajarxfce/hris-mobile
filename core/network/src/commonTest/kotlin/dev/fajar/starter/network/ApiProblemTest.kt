package dev.fajar.starter.network

import dev.fajar.starter.common.result.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class ApiProblemTest {
    @Test
    fun problemCodesArePreservedForLocalizationWithoutServerProse() = runTest {
        val reference = "2cacb83f-0c36-4356-88a6-8020d03f1111"
        for ((status, kind) in
            listOf(
                403 to FailureKind.AccessDenied,
                409 to FailureKind.Conflict,
                422 to FailureKind.Validation,
                429 to FailureKind.Service,
                503 to FailureKind.Service,
            )) {
            val client =
                createHttpClient(
                    MockEngine {
                        respond(
                            """{"title":"private title","detail":"private diagnostic","status":200,"code":"record_changed","fields":{"version":"stale_version","email":"invalid"},"parameters":{"expectedVersion":"4"},"correlationId":"$reference","retryAfterSeconds":12}""",
                            HttpStatusCode.fromValue(status),
                            headersOf(HttpHeaders.ContentType, "application/problem+json"),
                        )
                    },
                    "https://example.invalid/",
                )
            try {
                val failure =
                    assertIs<AppResult.Failed>(safeApiCall { client.requestJson<Unit>("record") })
                        .failure
                assertEquals(kind, failure.kind)
                assertEquals("record_changed", failure.code)
                assertEquals(
                    mapOf("version" to "stale_version", "email" to "invalid"),
                    failure.fieldCodes,
                )
                assertEquals(
                    mapOf(
                        "version" to ValidationIssue.Rejected,
                        "email" to ValidationIssue.Invalid,
                    ),
                    failure.violations,
                )
                assertEquals(mapOf("expectedVersion" to "4"), failure.parameters)
                assertEquals(reference, failure.correlationId)
                assertEquals(12L, failure.retryAfterSeconds)
                assertFalse(failure.message.contains("private"))
                assertFalse(failure.toString().contains("expectedVersion"))
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun metadataIsBoundedAndInvalidValuesCannotOverrideTheHttpFailure() {
        val fallback = mapHttpStatusFailure(409)
        val reference = "2cacb83f-0c36-4356-88a6-8020d03f1111"
        val failure =
            parseApiFailure(
                """{"code":"private diagnostic","fields":{"../bad":"invalid","name":"required"},"parameters":{"version":"4","secret":{"nested":true},"../bad":"ignored","newline":"a\nb"},"retryAfterSeconds":999999}""",
                fallback,
                "30",
                reference,
            )
        assertEquals(FailureKind.Conflict, failure.kind)
        assertNull(failure.code)
        assertEquals(mapOf("name" to "required"), failure.fieldCodes)
        assertEquals(mapOf("version" to "4"), failure.parameters)
        assertEquals(reference, failure.correlationId)
        assertEquals(30L, failure.retryAfterSeconds)
        assertNull(parseApiFailure("""{"retryAfterSeconds":999999}""", fallback).retryAfterSeconds)
        val manyFields = (1..100).joinToString(",") { "\"field$it\":\"invalid\"" }
        assertEquals(32, parseApiFailure("{\"fields\":{$manyFields}}", fallback).fieldCodes.size)
    }

    @Test
    fun malformedOversizedAndDeepProblemsKeepTheirStatusAndHeaderMetadata() {
        val fallback = mapHttpStatusFailure(503)
        for (body in
            listOf("not JSON", " ".repeat(16_385), "[".repeat(65) + "0" + "]".repeat(65))) {
            val failure = parseApiFailure(body, fallback, "5")
            assertEquals(FailureKind.Service, failure.kind)
            assertNull(failure.code)
            assertEquals(5L, failure.retryAfterSeconds)
        }
    }
}
