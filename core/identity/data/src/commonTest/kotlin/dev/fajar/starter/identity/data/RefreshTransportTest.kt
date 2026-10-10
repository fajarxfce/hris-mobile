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
            assertEquals("/api/v1/auth/native/refresh", request.url.encodedPath)
            assertEquals(
                """{"refreshToken":"original"}""",
                request.body.toByteArray().decodeToString(),
            )
            respond(
                """{"sessionId":"22222222-2222-4222-8222-222222222222","accessToken":"renewed","refreshToken":"next","accessExpiresAt":"2099-01-01T00:00:00Z","sessionExpiresAt":"2099-02-01T00:00:00Z","tokenType":"Bearer"}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = createHttpClient(engine, "https://example.invalid/api/v1/")
        try {
            assertEquals(
                "next",
                AuthApi(client).refresh(RefreshRequest("original"), operation).refreshToken,
            )
        } finally {
            client.close()
            engine.close()
        }
    }
}
