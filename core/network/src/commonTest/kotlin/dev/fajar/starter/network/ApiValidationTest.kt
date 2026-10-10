package dev.fajar.starter.network

import dev.fajar.starter.common.result.*
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class ApiValidationTest {
    @Test
    fun backendFieldCodesAreMappedWithoutRenderingServerMessages() = runTest {
        val client =
            createHttpClient(
                MockEngine {
                    respond(
                        """{"errors":{"email":"invalid","password":"secret server diagnostic","../unsafe":"invalid"}}""",
                        HttpStatusCode.UnprocessableEntity,
                    )
                },
                "https://example.test/",
            )
        try {
            val failure = assertIs<AppResult.Failed>(safeApiCall { client.get("login") }).failure
            assertEquals(
                mapOf("email" to ValidationIssue.Invalid, "password" to ValidationIssue.Rejected),
                failure.violations,
            )
            assertFalse(failure.toString().contains("secret"))
        } finally {
            client.close()
        }
    }

    @Test
    fun malformedValidationBodyKeepsTheHttpFailureClassification() = runTest {
        val client =
            createHttpClient(
                MockEngine { respond("not json", HttpStatusCode.UnprocessableEntity) },
                "https://example.test/",
            )
        try {
            assertEquals(
                FailureKind.Validation,
                assertIs<AppResult.Failed>(safeApiCall { client.get("login") }).failure.kind,
            )
        } finally {
            client.close()
        }
    }
}
