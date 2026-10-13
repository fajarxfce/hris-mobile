package dev.fajar.starter.network

import dev.fajar.starter.common.result.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.request.prepareGet
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class ApiResponseBodyTest {
    @Test
    fun aBoundedBinaryProbeReturnsOwnedBytesAndRejectsUnexpectedLargerBodies() = runTest {
        for (declared in listOf(false, true)) {
            val client =
                HttpClient(
                    MockEngine {
                        respond(
                            byteArrayOf(37),
                            HttpStatusCode.PartialContent,
                            if (declared) headersOf(HttpHeaders.ContentLength, "1") else headersOf(),
                        )
                    }
                )
            try {
                val bytes =
                    client.prepareGet("https://api.example.test/content").execute {
                        readApiBody(it, 1)
                    }
                assertContentEquals(byteArrayOf(37), bytes)
                bytes.fill(0)
                assertIs<AppResult.Failed>(
                    safeApiCall {
                        client.prepareGet("https://api.example.test/content").execute {
                            readApiBody(it, 0)
                        }
                    }
                )
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun invalidBudgetsAreRejectedAndAnHttpProblemKeepsItsOwnBound() = runTest {
        val client =
            HttpClient(
                MockEngine {
                    respond(
                        """{"status":403,"code":"mfa_required","detail":"private detail"}""",
                        HttpStatusCode.Forbidden,
                        headersOf(HttpHeaders.ContentType, "application/problem+json"),
                    )
                }
            )
        try {
            for (limit in listOf(-1, 16_777_217)) {
                assertFailsWith<IllegalArgumentException> {
                    client.prepareGet("https://api.example.test/content").execute {
                        readApiBody(it, limit)
                    }
                }
            }
            val result = safeApiCall {
                client.prepareGet("https://api.example.test/content").execute { readApiBody(it, 1) }
            }
            val failure = assertIs<AppResult.Failed>(result).failure
            assertEquals("mfa_required", failure.code)
            assertFalse(failure.message.contains("private detail"))
        } finally {
            client.close()
        }
    }
}
