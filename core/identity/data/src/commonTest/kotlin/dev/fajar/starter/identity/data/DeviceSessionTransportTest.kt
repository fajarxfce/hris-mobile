package dev.fajar.starter.identity.data

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.data.api.DeviceSessionApi
import dev.fajar.starter.identity.data.datasources.ApiDeviceSessionRemoteDataSource
import dev.fajar.starter.identity.data.repositories.RemoteDeviceSessionRepository
import dev.fajar.starter.identity.domain.entities.DeviceSession
import dev.fajar.starter.network.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class DeviceSessionTransportTest {
    private val id = "11111111-1111-4111-8111-111111111111"
    private val item =
        """{"id":"$id","deviceName":"Test Android","createdAt":"2026-10-11T00:00:00Z","expiresAt":"2026-11-11T00:00:00Z"}"""

    @Test
    fun mapsTheBoundedListAndAnEmptyRevocationResponse() = runTest {
        val methods = mutableListOf<HttpMethod>()
        val engine = MockEngine { request ->
            methods += request.method
            when (request.method) {
                HttpMethod.Get -> {
                    assertEquals("/api/v1/auth/native/sessions", request.url.encodedPath)
                    respond(
                        "[$item]",
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
                HttpMethod.Delete -> {
                    assertEquals("/api/v1/auth/native/sessions/$id", request.url.encodedPath)
                    respond("", HttpStatusCode.NoContent)
                }
                else -> error("Unexpected method")
            }
        }
        val client = createHttpClient(engine, "https://example.invalid/api/v1/")
        try {
            val repository =
                RemoteDeviceSessionRepository(
                    ApiDeviceSessionRemoteDataSource(DeviceSessionApi(client))
                )
            assertEquals(
                "Test Android",
                assertIs<AppResult.Success<List<DeviceSession>>>(repository.list("local"))
                    .value
                    .single()
                    .deviceName,
            )
            assertEquals(AppResult.Success(Unit), repository.revoke("local", id))
            assertEquals(listOf(HttpMethod.Get, HttpMethod.Delete), methods)
        } finally {
            client.close()
        }
    }

    @Test
    fun rejectsDuplicateMalformedOversizedAndExcessiveSessionLists() = runTest {
        for (body in
            listOf(
                "[$item,$item]",
                "[${item.replace(id, "invalid")}]",
                "[${List(51) { index -> item.replace(id, "11111111-1111-4111-8111-${index.toString().padStart(12, '0')}") }.joinToString()}]",
                "[${item.replace("2026-11-11", "2026-01-01")}]",
                "[${item.replace("Test Android", "x".repeat(101))}]",
                " ".repeat(32_769),
            )) {
            val client =
                createHttpClient(
                    MockEngine {
                        respond(
                            body,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                    "https://example.invalid/api/v1/",
                )
            try {
                assertIs<AppResult.Failed>(
                    RemoteDeviceSessionRepository(
                            ApiDeviceSessionRemoteDataSource(DeviceSessionApi(client))
                        )
                        .list("local")
                )
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun preservesServerErrorCodesWithoutExposingRawDiagnosticsOrRetrying() = runTest {
        var calls = 0
        val client =
            createHttpClient(
                MockEngine {
                    calls++
                    respond(
                        """{"code":"native_session_not_found","detail":"private device data"}""",
                        HttpStatusCode.NotFound,
                        headersOf(HttpHeaders.ContentType, "application/problem+json"),
                    )
                },
                "https://example.invalid/api/v1/",
            )
        try {
            val result =
                RemoteDeviceSessionRepository(
                        ApiDeviceSessionRemoteDataSource(DeviceSessionApi(client))
                    )
                    .revoke("local", id)
            val failure = assertIs<AppResult.Failed>(result).failure
            assertEquals("native_session_not_found", failure.code)
            assertFalse(failure.message.contains("private"))
            assertEquals(1, calls)
        } finally {
            client.close()
        }
    }
}
