package dev.fajar.starter.identity.data

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.data.firebase.api.FirebaseProviderApi
import dev.fajar.starter.identity.data.firebase.boundary.safeFirebaseAuthenticationCall
import dev.fajar.starter.identity.data.firebase.dto.*
import dev.fajar.starter.identity.data.firebase.mappers.validated
import dev.fajar.starter.network.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

class FirebaseProviderTransportTest {
    private val options =
        FirebaseClientOptions(
            "cmp-fajar",
            "A".repeat(39),
            androidPackage = "dev.fajar.hris.mobile.dev",
            androidCertificate = "A".repeat(40),
        )
    private val token = "firebase-proof".repeat(5)
    private val success =
        """{"idToken":"$token","refreshToken":"discard-this-refresh","email":"private@example.test"}"""

    @Test
    fun passwordAndGoogleUseOnlyTheirPinnedCredentialProtocol() = runTest {
        val seen = mutableListOf<String>()
        val client =
            createHttpClient(
                MockEngine { request ->
                    assertEquals("identitytoolkit.googleapis.com", request.url.host)
                    assertEquals(URLProtocol.HTTPS, request.url.protocol)
                    assertEquals(HttpMethod.Post, request.method)
                    assertEquals(options.apiKey, request.url.parameters["key"])
                    assertEquals(options.androidPackage, request.headers["X-Android-Package"])
                    assertEquals(options.androidCertificate, request.headers["X-Android-Cert"])
                    assertNull(request.headers["X-Ios-Bundle-Identifier"])
                    for (name in
                        listOf(
                            HttpHeaders.Authorization,
                            HttpHeaders.Cookie,
                            "traceparent",
                            "baggage",
                        )) assertNull(request.headers[name])
                    val body =
                        Json.parseToJsonElement(
                                (request.body as OutgoingContent.ByteArrayContent)
                                    .bytes()
                                    .decodeToString()
                            )
                            .jsonObject
                    assertEquals(true, body["returnSecureToken"]!!.jsonPrimitive.boolean)
                    assertNull(body["idToken"])
                    when (request.url.encodedPath) {
                        "/v1/accounts:signInWithPassword" -> {
                            assertEquals(
                                "fixture@example.test",
                                body["email"]!!.jsonPrimitive.content,
                            )
                            assertEquals(
                                "fixture-password",
                                body["password"]!!.jsonPrimitive.content,
                            )
                        }
                        "/v1/accounts:signInWithIdp" -> {
                            val form = parseQueryString(body["postBody"]!!.jsonPrimitive.content)
                            assertEquals("google.com", form["providerId"])
                            assertEquals("google-proof".repeat(5), form["id_token"])
                            assertEquals(
                                "http://localhost",
                                body["requestUri"]!!.jsonPrimitive.content,
                            )
                            assertFalse(body["returnIdpCredential"]!!.jsonPrimitive.boolean)
                            assertNull(body["password"])
                        }
                        else -> error("Unexpected provider path")
                    }
                    seen += request.url.encodedPath
                    respond(
                        success,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                },
                "https://firebase-auth.invalid/",
            )
        try {
            val api = FirebaseProviderApi(client)
            assertEquals(
                token,
                api.password(
                        options,
                        FirebasePasswordRequest("fixture@example.test", "fixture-password"),
                    )
                    .idToken,
            )
            val google = api.google(options, "google-proof".repeat(5))
            assertEquals(token, google.idToken)
            assertFalse(google.toString().contains("discard-this-refresh"))
            assertFalse(google.toString().contains(token))
            assertEquals(2, seen.size)
        } finally {
            client.cancelAndClose()
        }
    }

    @Test
    fun applePasswordRequestUsesItsBundleRestrictionWithoutAndroidOrHrisCredentials() = runTest {
        val apple =
            FirebaseClientOptions(
                    "fixture-project",
                    "A".repeat(39),
                    iosBundleId = "dev.fajar.fixture",
                )
                .validated()
        var calls = 0
        val client =
            createHttpClient(
                MockEngine { request ->
                    calls++
                    assertEquals("identitytoolkit.googleapis.com", request.url.host)
                    assertEquals("/v1/accounts:signInWithPassword", request.url.encodedPath)
                    assertEquals(apple.iosBundleId, request.headers["X-Ios-Bundle-Identifier"])
                    for (name in
                        listOf(
                            "X-Android-Package",
                            "X-Android-Cert",
                            HttpHeaders.Authorization,
                            HttpHeaders.Cookie,
                            "traceparent",
                            "baggage",
                        )) {
                        assertNull(request.headers[name])
                    }
                    respond(
                        success,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                },
                "https://firebase-auth.invalid/",
            )
        try {
            assertEquals(
                token,
                FirebaseProviderApi(client)
                    .password(
                        apple,
                        FirebasePasswordRequest("fixture@example.test", "fixture-password"),
                    )
                    .idToken,
            )
            assertEquals(1, calls)
        } finally {
            client.cancelAndClose()
        }
    }

    @Test
    fun invalidOrMixedAppleAndAndroidMetadataIsRejectedBeforeProviderAcquisition() {
        for (bundle in listOf("", "dev.fixture\r\nOther: value", "dev/fixture", "a".repeat(256))) {
            assertFailsWith<IllegalArgumentException> {
                FirebaseClientOptions("fixture-project", "A".repeat(39), iosBundleId = bundle)
                    .validated()
            }
        }
        assertFailsWith<IllegalArgumentException> {
            options.copy(iosBundleId = "dev.fajar.fixture").validated()
        }
    }

    @Test
    fun firebaseErrorsAreClassifiedWithoutProviderMessagesOrAutomaticRetry() = runTest {
        for ((code, status, kind, expected) in
            listOf(
                ErrorCase(
                    "INVALID_LOGIN_CREDENTIALS",
                    400,
                    FailureKind.Unauthorized,
                    "firebase_sign_in_failed",
                ),
                ErrorCase(
                    "OPERATION_NOT_ALLOWED",
                    400,
                    FailureKind.Unavailable,
                    "identity.firebase_unavailable",
                ),
                ErrorCase(
                    "TOO_MANY_ATTEMPTS_TRY_LATER",
                    400,
                    FailureKind.Service,
                    "sign_in_rate_limited",
                ),
                ErrorCase(
                    "BACKEND_FAILURE",
                    503,
                    FailureKind.Unavailable,
                    "identity.firebase_unavailable",
                ),
            )) {
            var calls = 0
            val client =
                createHttpClient(
                    MockEngine {
                        calls++
                        respond(
                            """{"error":{"message":"$code : private@example.test"}}""",
                            HttpStatusCode.fromValue(status),
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                    "https://firebase-auth.invalid/",
                )
            try {
                val result =
                    assertIs<AppResult.Failed>(
                        safeFirebaseAuthenticationCall {
                            FirebaseProviderApi(client)
                                .password(
                                    options,
                                    FirebasePasswordRequest(
                                        "fixture@example.test",
                                        "fixture-password",
                                    ),
                                )
                        }
                    )
                assertEquals(kind, result.failure.kind)
                assertEquals(expected, result.failure.code)
                assertFalse(result.toString().contains("private@example.test"))
                assertEquals(1, calls)
            } finally {
                client.cancelAndClose()
            }
        }
    }

    private data class ErrorCase(
        val code: String,
        val status: Int,
        val kind: FailureKind,
        val expected: String,
    )

    @Test
    fun oversizeEncodingContentTypesAndRedirectsNeverProduceProof() = runTest {
        for (mode in 0..4) {
            var calls = 0
            val client =
                createHttpClient(
                    MockEngine {
                        calls++
                        when (mode) {
                            0 ->
                                respond(
                                    " ".repeat(32_769),
                                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                                )
                            1 ->
                                respond(
                                    success,
                                    headers = headersOf(HttpHeaders.ContentType, "text/html"),
                                )
                            2 ->
                                respond(
                                    success,
                                    headers =
                                        headersOf(
                                            HttpHeaders.ContentType to listOf("application/json"),
                                            HttpHeaders.ContentEncoding to listOf("gzip"),
                                        ),
                                )
                            3 ->
                                respond(
                                    " ".repeat(4097),
                                    HttpStatusCode.BadRequest,
                                    headersOf(HttpHeaders.ContentType, "application/json"),
                                )
                            else ->
                                respond(
                                    "",
                                    HttpStatusCode.Found,
                                    headersOf(HttpHeaders.Location, "https://another.invalid/"),
                                )
                        }
                    },
                    "https://firebase-auth.invalid/",
                )
            try {
                assertIs<AppResult.Failed>(
                    safeFirebaseAuthenticationCall {
                        FirebaseProviderApi(client).google(options, "google-proof".repeat(5))
                    }
                )
                assertEquals(1, calls)
            } finally {
                client.cancelAndClose()
            }
        }
    }

    @Test
    fun cancellationOwnsTheProviderRequestAndClosesPendingIo() = runTest {
        val entered = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        val client =
            createHttpClient(
                MockEngine {
                    entered.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        released.complete(Unit)
                    }
                },
                "https://firebase-auth.invalid/",
            )
        try {
            val work = async {
                safeFirebaseAuthenticationCall {
                    FirebaseProviderApi(client).google(options, "google-proof".repeat(5))
                }
            }
            entered.await()
            work.cancelAndJoin()
            withContext(Dispatchers.Default) { withTimeout(5_000) { released.await() } }
        } finally {
            client.cancelAndClose()
        }
    }
}
