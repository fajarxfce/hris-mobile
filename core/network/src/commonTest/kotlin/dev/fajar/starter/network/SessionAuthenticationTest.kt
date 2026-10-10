package dev.fajar.starter.network

import dev.fajar.starter.common.result.*
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class SessionAuthenticationTest {
    @Test
    fun one401ReplaysWithRotatedCredentialsAndSameScope() = runTest {
        val headers = mutableListOf<String?>()
        val rejected = mutableListOf<String?>()
        val client =
            createHttpClient(
                MockEngine {
                    headers += it.headers[HttpHeaders.Authorization]
                    if (headers.size == 1) respond("", HttpStatusCode.Unauthorized)
                    else respond("ok")
                },
                HttpClientSettings("https://api.example.test/"),
            ) {
                install(SessionAuthentication) {
                    origin = Url("https://api.example.test/")
                    acquire = { scope, token ->
                        assertEquals("session-a", scope)
                        rejected.add(token)
                        AppResult.Success(if (token == null) "first" else "rotated")
                    }
                }
            }
        try {
            assertEquals("ok", client.get("records") { forSession("session-a") }.bodyAsText())
            assertEquals<List<String?>>(listOf("Bearer first", "Bearer rotated"), headers)
            assertEquals<List<String?>>(listOf(null, "first"), rejected)
        } finally {
            client.close()
        }
    }

    @Test
    fun originMismatchNeverAcquiresOrSendsCredentials() = runTest {
        var sent = 0
        val client =
            createHttpClient(
                MockEngine {
                    sent++
                    respond("bad")
                },
                HttpClientSettings("https://api.example.test/"),
            ) {
                install(SessionAuthentication) {
                    origin = Url("https://api.example.test/")
                    acquire = { _, _ -> error("Must not read credentials") }
                }
            }
        try {
            assertFailsWith<IllegalArgumentException> {
                client.get("https://elsewhere.example.test/") { forSession("a") }
            }
            assertEquals(0, sent)
        } finally {
            client.close()
        }
    }

    @Test
    fun accountChangePreventsReplayAndRefreshFailuresKeepTheirClassification() = runTest {
        var sent = 0
        var refreshResult: AppResult<String?> = AppResult.Success(null)
        val client =
            createHttpClient(
                MockEngine {
                    sent++
                    respond("", HttpStatusCode.Unauthorized)
                },
                HttpClientSettings("https://api.example.test/"),
            ) {
                install(SessionAuthentication) {
                    origin = Url("https://api.example.test/")
                    acquire = { _, rejected ->
                        if (rejected == null) AppResult.Success("old") else refreshResult
                    }
                }
            }
        try {
            assertEquals(
                FailureKind.Unauthorized,
                (safeApiCall { client.get { forSession("old-session") } } as AppResult.Failed)
                    .failure
                    .kind,
            )
            assertEquals(1, sent)
            val failure = Failure(FailureKind.Storage, "Storage unavailable")
            refreshResult = AppResult.Failed(failure)
            assertSame(
                failure,
                (safeApiCall { client.get { forSession("old-session") } } as AppResult.Failed)
                    .failure,
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun unsafePostIsNotReplayedAndRepeated401StopsAfterOneRetry() = runTest {
        var sent = 0
        val client =
            createHttpClient(
                MockEngine {
                    sent++
                    respond("", HttpStatusCode.Unauthorized)
                },
                HttpClientSettings("https://api.example.test/"),
            ) {
                install(SessionAuthentication) {
                    origin = Url("https://api.example.test/")
                    acquire = { _, rejected ->
                        AppResult.Success(if (rejected == null) "first" else "next")
                    }
                }
            }
        try {
            assertIs<AppResult.Failed>(
                safeApiCall {
                    client.post {
                        forSession("a")
                        setBody("value")
                    }
                }
            )
            assertEquals(1, sent)
            assertIs<AppResult.Failed>(safeApiCall { client.get { forSession("a") } })
            assertEquals(3, sent)
        } finally {
            client.close()
        }
    }
}
