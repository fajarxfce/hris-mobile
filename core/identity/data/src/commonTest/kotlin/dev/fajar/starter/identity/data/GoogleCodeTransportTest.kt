package dev.fajar.starter.identity.data

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.identity.data.firebase.api.FirebaseProviderApi
import dev.fajar.starter.identity.data.firebase.boundary.safeFirebaseAuthenticationCall
import dev.fajar.starter.identity.data.firebase.dto.GoogleCredentialResult
import dev.fajar.starter.identity.data.firebase.mappers.googleRedirectUri
import dev.fajar.starter.network.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class GoogleCodeTransportTest {
    private val clientId = "123-ios.apps.googleusercontent.com"
    private val credential =
        GoogleCredentialResult.AuthorizationCode(
            "owned+code",
            "v".repeat(43),
            "n".repeat(43),
            googleRedirectUri(clientId),
        )
    private val token = "google-proof".repeat(5)

    @Test
    fun codeExchangeUsesOnlyItsPinnedFormAndRetainsNoAccessOrRefreshCredential() = runTest {
        val client =
            createHttpClient(
                MockEngine { request ->
                    assertEquals("https://oauth2.googleapis.com/token", request.url.toString())
                    assertEquals(HttpMethod.Post, request.method)
                    for (name in
                        listOf(
                            HttpHeaders.Authorization,
                            HttpHeaders.Cookie,
                            "X-Ios-Bundle-Identifier",
                            "X-Android-Package",
                            "traceparent",
                            "baggage",
                        )) assertNull(request.headers[name])
                    val form =
                        parseQueryString(
                            (request.body as OutgoingContent.ByteArrayContent)
                                .bytes()
                                .decodeToString()
                        )
                    assertEquals(
                        setOf("client_id", "code", "code_verifier", "redirect_uri", "grant_type"),
                        form.names(),
                    )
                    assertEquals(clientId, form["client_id"])
                    assertEquals(credential.code, form["code"])
                    assertEquals(credential.verifier, form["code_verifier"])
                    assertEquals(credential.redirectUri, form["redirect_uri"])
                    assertEquals("authorization_code", form["grant_type"])
                    respond(
                        """{"id_token":"$token","access_token":"discard-access","refresh_token":"discard-refresh"}""",
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                },
                "https://firebase-auth.invalid/",
            )
        try {
            assertEquals(token, FirebaseProviderApi(client).googleCode(clientId, credential))
        } finally {
            client.cancelAndClose()
        }
    }

    @Test
    fun invalidRedirectOrVerifierNeverSendsACode() = runTest {
        var calls = 0
        val client =
            createHttpClient(
                MockEngine {
                    calls++
                    error("No request expected")
                },
                "https://firebase-auth.invalid/",
            )
        try {
            for (value in
                listOf(
                    credential.copy(redirectUri = "https://untrusted.invalid/"),
                    credential.copy(verifier = "short"),
                    credential.copy(code = "bad\r\ncode"),
                )) assertFailsWith<IllegalArgumentException> {
                FirebaseProviderApi(client).googleCode(clientId, value)
            }
            assertEquals(0, calls)
        } finally {
            client.cancelAndClose()
        }
    }

    @Test
    fun malformedOversizedEncodedErrorAndRedirectResponsesCannotYieldProofOrRetry() = runTest {
        for (mode in 0..6) {
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
                                    "{}",
                                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                                )
                            2 ->
                                respond(
                                    """{"id_token":42}""",
                                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                                )
                            3 ->
                                respond(
                                    "private-provider-page",
                                    headers = headersOf(HttpHeaders.ContentType, "text/html"),
                                )
                            4 ->
                                respond(
                                    "{}",
                                    headers =
                                        headersOf(
                                            HttpHeaders.ContentType to listOf("application/json"),
                                            HttpHeaders.ContentEncoding to listOf("gzip"),
                                        ),
                                )
                            5 ->
                                respond(
                                    """{"error":"invalid_grant","error_description":"private-provider-detail"}""",
                                    HttpStatusCode.BadRequest,
                                    headersOf(HttpHeaders.ContentType, "application/json"),
                                )
                            else ->
                                respond(
                                    "{}",
                                    HttpStatusCode.Found,
                                    headersOf(
                                        HttpHeaders.Location to
                                            listOf("https://untrusted.invalid/"),
                                        HttpHeaders.ContentType to listOf("application/json"),
                                    ),
                                )
                        }
                    },
                    "https://firebase-auth.invalid/",
                )
            try {
                val result = safeFirebaseAuthenticationCall {
                    FirebaseProviderApi(client).googleCode(clientId, credential)
                }
                assertIs<AppResult.Failed>(result)
                assertFalse(result.toString().contains("private-provider"))
                assertEquals(1, calls)
            } finally {
                client.cancelAndClose()
            }
        }
    }

    @Test
    fun cancellationReleasesTheCodeRequestBeforeTheClientIsClosed() = runTest {
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
            val pending = async { FirebaseProviderApi(client).googleCode(clientId, credential) }
            entered.await()
            pending.cancelAndJoin()
            withContext(Dispatchers.Default) { withTimeout(5_000) { released.await() } }
        } finally {
            client.cancelAndClose()
        }
    }
}
