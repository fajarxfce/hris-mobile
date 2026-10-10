package dev.fajar.starter.identity.data

import dev.fajar.starter.identity.data.api.AuthApi
import dev.fajar.starter.identity.data.dto.RefreshRequest
import dev.fajar.starter.network.createHttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class RefreshTransportTest {
    @Test
    fun refreshSendsTheUseCasesOperationKeyWithoutChangingItsPayload() = runTest {
        val operation = "11111111-1111-4111-8111-111111111111"
        val engine = MockEngine { request ->
            assertEquals(operation, request.headers["Idempotency-Key"])
            assertEquals("/auth/refresh", request.url.encodedPath)
            assertEquals(
                """{"refreshToken":"original"}""",
                request.body.toByteArray().decodeToString(),
            )
            respond(
                """{"user":{"id":"one","name":"Example","email":"example@example.invalid"},"tokens":{"accessToken":"renewed","refreshToken":"next","expiresAtEpochMillis":9999999999999}}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = createHttpClient(engine, "https://example.invalid/")
        try {
            assertEquals(
                "next",
                AuthApi(client).refresh(RefreshRequest("original"), operation).tokens.refreshToken,
            )
        } finally {
            client.close()
            engine.close()
        }
    }
}
