@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter.network

import dev.fajar.starter.common.result.*
import io.ktor.client.engine.js.JsError
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class BrowserFetchFailureTest {
    @Test
    fun realFetchRejectionBecomesNetworkFailure() = runTest {
        // Browsers reject this unsafe loopback port before opening a network connection.
        val client =
            createHttpClient(
                createPlatformHttpEngine(),
                HttpClientSettings("http://127.0.0.1:1/", allowCleartext = true),
            )
        try {
            val result = safeApiCall { client.get("resource").bodyAsText() }
            assertEquals(FailureKind.Network, assertIs<AppResult.Failed>(result).failure.kind)
        } finally {
            client.close()
        }
    }

    @Test
    fun sdkFetchAndStreamWrappersAreHandledButProgrammingErrorsAreNotHidden() = runTest {
        for (cause in
            listOf(
                JsError(browserTypeError()),
                Error("Fetch failed", JsError(browserTypeError())),
            )) {
            val result = safeApiCall { throw cause }
            assertEquals(FailureKind.Network, assertIs<AppResult.Failed>(result).failure.kind)
        }
        assertFailsWith<AssertionError> {
            safeApiCall { throw AssertionError("Programming error") }
        }
    }
}

@JsFun("() => new TypeError('Fixture transport failure')")
private external fun browserTypeError(): JsAny
