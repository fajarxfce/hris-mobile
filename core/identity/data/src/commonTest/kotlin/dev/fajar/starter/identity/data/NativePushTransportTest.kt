package dev.fajar.starter.identity.data

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.data.push.api.NativePushApi
import dev.fajar.starter.identity.data.push.datasources.ApiNativePushRemoteSource
import dev.fajar.starter.identity.data.push.mappers.toDto
import dev.fajar.starter.identity.data.push.mappers.toState
import dev.fajar.starter.identity.data.push.repositories.RemoteNativePushRegistrationRepository
import dev.fajar.starter.identity.domain.push.entities.*
import dev.fajar.starter.network.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*

class NativePushTransportTest {
    private val family = "22222222-2222-4222-8222-222222222222"
    private val operation = "33333333-3333-4333-8333-333333333333"
    private val owner =
        NativePushOwner(
            "local",
            "https://hris.invalid/api/v1/",
            "11111111-1111-4111-8111-111111111111",
            family,
            Instant.parse("2027-01-01T00:00:00Z"),
        )
    private val metadata =
        """{"sessionId":"$family","platform":"ANDROID","enabled":true,"version":0,"registeredAt":"2026-10-28T00:00:00Z","updatedAt":"2026-10-28T00:00:00Z","expiresAt":"2026-11-27T00:00:00Z"}"""

    @Test
    fun registrationUsesTheNativeSessionAndReplaysExactlyTheFrozenRequest() = runTest {
        val requests = mutableListOf<Pair<HttpMethod, String>>()
        val client =
            createHttpClient(
                MockEngine { request ->
                    assertEquals(null, request.headers[HttpHeaders.Cookie])
                    when (request.method) {
                        HttpMethod.Get -> respond(metadata, headers = jsonHeaders)
                        else -> {
                            assertEquals(operation, request.headers["Idempotency-Key"])
                            val body = (request.body as TextContent).text
                            requests += request.method to body
                            val value = Json.parseToJsonElement(body).jsonObject
                            assertFalse("accountId" in value || "sessionId" in value)
                            if (request.method == HttpMethod.Put) {
                                assertEquals(
                                    "/api/v1/auth/native/push-registration",
                                    request.url.encodedPath,
                                )
                                assertEquals(
                                    "a".repeat(32),
                                    value.getValue("token").jsonPrimitive.content,
                                )
                            } else {
                                assertEquals(
                                    "/api/v1/auth/native/push-registration/disable",
                                    request.url.encodedPath,
                                )
                                assertEquals(setOf("expectedVersion"), value.keys)
                            }
                            respond("""{"id":"$family","version":0}""", headers = jsonHeaders)
                        }
                    }
                },
                owner.audience,
            )
        try {
            val repository = repository(client)
            val registered =
                assertNotNull(
                    assertIs<AppResult.Success<NativePushRegistration?>>(repository.read(owner))
                        .value
                )
            assertEquals(NativePushPlatform.ANDROID, registered.platform)
            val save =
                NativePushCommand.Save(operation, null, NativePushPlatform.ANDROID, "a".repeat(32))
            repeat(2) {
                assertEquals(
                    AppResult.Success(NativePushReceipt(family, 0)),
                    repository.send(owner, save),
                )
            }
            assertEquals(requests[0], requests[1])
            assertEquals(
                AppResult.Success(NativePushReceipt(family, 0)),
                repository.send(owner, NativePushCommand.Disable(operation, 0)),
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun sessionMillisecondPrecisionAdmitsSubmillisecondMetadataWithoutExtendingExpiry() = runTest {
        val current = owner.copy(expiresAt = Instant.parse("2026-11-27T00:00:00.123Z"))
        for ((expiry, accepted) in
            listOf(
                "2026-11-27T00:00:00.123855Z" to true,
                "2026-11-27T00:00:00.123999999Z" to true,
                "2026-11-27T00:00:00.124Z" to false,
                "2026-11-27T00:00:01Z" to false,
            )) {
            val body = metadata.replace("2026-11-27T00:00:00Z", expiry)
            val client =
                createHttpClient(
                    MockEngine { respond(body, headers = jsonHeaders) },
                    owner.audience,
                )
            try {
                val result = repository(client).read(current)
                if (!accepted) assertIs<AppResult.Failed>(result)
                else {
                    val registration =
                        assertNotNull(
                            assertIs<AppResult.Success<NativePushRegistration?>>(result).value
                        )
                    assertEquals(current.expiresAt, registration.expiresAt)
                    val state =
                        NativePushState(
                            desired = true,
                            owner = current,
                            token = "a".repeat(32),
                            registration = registration,
                        )
                    assertEquals(state, state.toDto().toState())
                }
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun onlyTheExactMissingRegistrationResponseIsAnEmptyState() = runTest {
        for ((status, code, empty) in
            listOf(
                Triple(404, "push_registration_not_found", true),
                Triple(404, "native_session_not_found", false),
                Triple(403, "push_registration_not_found", false),
                Triple(403, "mfa_required", false),
            )) {
            var calls = 0
            val client =
                createHttpClient(
                    MockEngine {
                        calls++
                        respond(
                            """{"code":"$code","detail":"private provider details"}""",
                            HttpStatusCode.fromValue(status),
                            headersOf(HttpHeaders.ContentType, "application/problem+json"),
                        )
                    },
                    owner.audience,
                )
            try {
                val result = repository(client).read(owner)
                if (empty) assertEquals(AppResult.Success(null), result)
                else {
                    val failure = assertIs<AppResult.Failed>(result).failure
                    assertEquals(code, failure.code)
                    assertFalse(failure.message.contains("private provider"))
                }
                assertEquals(1, calls)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun malformedForeignAndUnboundedMetadataCannotBecomeCurrentRegistration() = runTest {
        for (body in
            listOf(
                metadata.replace(family, operation),
                metadata.replace("ANDROID", "WEB"),
                metadata.replace("\"version\":0", "\"version\":10001"),
                metadata.replace("2026-11-27", "2028-11-27"),
                metadata.replace("2026-11-27", "2026-10-27"),
                " ".repeat(4097),
            )) {
            val client =
                createHttpClient(
                    MockEngine { respond(body, headers = jsonHeaders) },
                    owner.audience,
                )
            try {
                assertIs<AppResult.Failed>(repository(client).read(owner))
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun anUnrelatedOrImpossibleReceiptDoesNotAcknowledgeThePendingCommand() = runTest {
        for (body in
            listOf("""{"id":"$operation","version":1}""", """{"id":"$family","version":9}""")) {
            val client =
                createHttpClient(
                    MockEngine { respond(body, headers = jsonHeaders) },
                    owner.audience,
                )
            try {
                assertIs<AppResult.Failed>(
                    repository(client)
                        .send(
                            owner,
                            NativePushCommand.Save(
                                operation,
                                0,
                                NativePushPlatform.ANDROID,
                                "a".repeat(32),
                            ),
                        )
                )
            } finally {
                client.close()
            }
        }
    }

    private fun repository(client: io.ktor.client.HttpClient) =
        RemoteNativePushRegistrationRepository(ApiNativePushRemoteSource(NativePushApi(client)))

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
}
