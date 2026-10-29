package dev.fajar.starter.identity.data

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.data.firebase.api.NativeFirebaseApi
import dev.fajar.starter.identity.data.firebase.datasources.ApiNativeFirebaseRemoteSource
import dev.fajar.starter.identity.data.firebase.dto.NativeFirebaseLoginDto
import dev.fajar.starter.identity.data.firebase.repositories.RemoteNativeFirebaseSessionRepository
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.firebase.entities.*
import dev.fajar.starter.network.*
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*

class NativeFirebaseTransportTest {
    private val operation = "22222222-2222-4222-8222-222222222222"
    private val id = "11111111-1111-4111-8111-111111111111"
    private val proof = "fixture-id-token".repeat(4)
    private val config =
        """{"enabled":true,"projectId":"cmp-fajar","providers":["password","google.com"]}"""
    private val session =
        """{"account":{"id":"$id","email":"fixture@example.test","displayName":"Fixture","mfaConfigured":false},"credentials":{"sessionId":"$id","accessToken":"hris-access","refreshToken":"hris-refresh","accessExpiresAt":"2026-10-28T01:00:00Z","sessionExpiresAt":"2026-11-28T00:00:00Z","tokenType":"Bearer"}}"""

    private fun repository(client: io.ktor.client.HttpClient) =
        RemoteNativeFirebaseSessionRepository(
            ApiNativeFirebaseRemoteSource(NativeFirebaseApi(client)),
            IdentityServer("https://hris.invalid/api/v1/", "Test device"),
        )

    @Test
    fun usesPublicNativeEndpointsAndMapsOnlyHrisCredentials() = runTest {
        val client =
            createHttpClient(
                MockEngine { request ->
                    assertNull(request.headers[HttpHeaders.Authorization])
                    when (request.method) {
                        HttpMethod.Get -> {
                            assertEquals("/api/v1/auth/native/firebase", request.url.encodedPath)
                            respond(
                                config,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }
                        HttpMethod.Post -> {
                            assertEquals(
                                "/api/v1/auth/native/firebase/login",
                                request.url.encodedPath,
                            )
                            assertEquals(operation, request.headers["Idempotency-Key"])
                            val body =
                                Json.parseToJsonElement(
                                        (request.body as OutgoingContent.ByteArrayContent)
                                            .bytes()
                                            .decodeToString()
                                    )
                                    .jsonObject
                            assertEquals(proof, body["idToken"]!!.jsonPrimitive.content)
                            assertEquals("Test device", body["deviceName"]!!.jsonPrimitive.content)
                            respond(
                                session,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }
                        else -> error("Unexpected method")
                    }
                },
                "https://hris.invalid/api/v1/",
            )
        try {
            val repository = repository(client)
            assertEquals(
                FirebaseSignInProvider.entries.toSet(),
                assertIs<AppResult.Success<FirebaseSignInConfiguration>>(repository.configuration())
                    .value
                    .providers,
            )
            val authenticated =
                assertIs<
                        AppResult.Success<
                            dev.fajar.starter.identity.domain.entities.AuthenticatedUser
                        >
                    >(
                        repository.exchange(proof, "Test device", operation)
                    )
                    .value
            assertEquals("hris-access", authenticated.tokens.accessToken)
            assertEquals("https://hris.invalid/api/v1/", authenticated.binding.audience)
            assertFalse(NativeFirebaseLoginDto(proof, "Test device").toString().contains(proof))
        } finally {
            client.cancelAndClose()
        }
    }

    @Test
    fun rejectsIncoherentDuplicateAndOversizedConfiguration() = runTest {
        for (body in
            listOf(
                config.replace("cmp-fajar", "../invalid"),
                config.replace("true", "false"),
                config.replace("google.com", "password"),
                config.replace("google.com", "x".repeat(65)),
                " ".repeat(4097),
            )) {
            val client =
                createHttpClient(
                    MockEngine {
                        respond(
                            body,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                    "https://hris.invalid/api/v1/",
                )
            try {
                assertIs<AppResult.Failed>(repository(client).configuration())
            } finally {
                client.cancelAndClose()
            }
        }
    }

    @Test
    fun additiveProvidersRemainUnavailableUntilTheClientSupportsThem() = runTest {
        val client =
            createHttpClient(
                MockEngine {
                    respond(
                        config.replace("google.com", "future-provider"),
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                },
                "https://hris.invalid/api/v1/",
            )
        try {
            assertEquals(
                setOf(FirebaseSignInProvider.EmailPassword),
                assertIs<AppResult.Success<FirebaseSignInConfiguration>>(
                        repository(client).configuration()
                    )
                    .value
                    .providers,
            )
        } finally {
            client.cancelAndClose()
        }
    }

    @Test
    fun serverFailuresPreserveTheirCodeAndOnlyExplicitRetryReusesTheCommand() = runTest {
        var calls = 0
        val bodies = mutableListOf<String>()
        val client =
            createHttpClient(
                MockEngine { request ->
                    calls++
                    assertEquals(operation, request.headers["Idempotency-Key"])
                    bodies +=
                        (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    if (calls == 1)
                        respond(
                            """{"code":"firebase_sign_in_unavailable","detail":"provider diagnostic"}""",
                            HttpStatusCode.ServiceUnavailable,
                            headersOf(HttpHeaders.ContentType, "application/problem+json"),
                        )
                    else
                        respond(
                            session,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                },
                "https://hris.invalid/api/v1/",
            )
        try {
            val repo = repository(client)
            val failed = assertIs<AppResult.Failed>(repo.exchange(proof, "Test device", operation))
            assertEquals("firebase_sign_in_unavailable", failed.failure.code)
            assertFalse(failed.toString().contains("provider diagnostic"))
            assertEquals(1, calls)
            assertIs<AppResult.Success<*>>(repo.exchange(proof, "Test device", operation))
            assertEquals(2, calls)
            assertEquals(bodies.first(), bodies.last())
        } finally {
            client.cancelAndClose()
        }
    }

    @Test
    fun oversizedOrInvalidCredentialsNeverBecomeAnAuthenticatedUser() = runTest {
        for (body in
            listOf(
                " ".repeat(16_385),
                session.replace("Bearer", "Other"),
                session.replace("2026-11-28", "2026-01-01"),
            )) {
            val client =
                createHttpClient(
                    MockEngine {
                        respond(
                            body,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                    "https://hris.invalid/api/v1/",
                )
            try {
                assertIs<AppResult.Failed>(
                    repository(client).exchange(proof, "Test device", operation)
                )
            } finally {
                client.cancelAndClose()
            }
        }
    }

    @Test
    fun callerCancellationReleasesThePendingTransportWithoutRetry() = runTest {
        val entered = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        var calls = 0
        val client =
            createHttpClient(
                MockEngine {
                    calls++
                    entered.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        released.complete(Unit)
                    }
                },
                "https://hris.invalid/api/v1/",
            )
        try {
            val pending = async { repository(client).exchange(proof, "Test device", operation) }
            entered.await()
            pending.cancelAndJoin()
            withContext(Dispatchers.Default) { withTimeout(5_000) { released.await() } }
            assertEquals(1, calls)
        } finally {
            client.cancelAndClose()
        }
    }
}
