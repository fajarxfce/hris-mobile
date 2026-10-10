package dev.fajar.starter.network

import dev.fajar.starter.common.result.*
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class ApplicationAvailabilityTest {
    @Test
    fun aBlockedPolicyPreventsTransportAndCanRecoverWithoutReplacingTheClient() = runTest {
        var calls = 0
        val failure = AppResult.Failed(Failure(FailureKind.Unavailable, "Maintenance"))
        var permitted = false
        val client =
            createHttpClient(
                MockEngine {
                    calls++
                    respond("ok")
                },
                HttpClientSettings("https://api.example.test/"),
            ) {
                install(ApplicationAvailability) {
                    check = { if (permitted) AppResult.Success(Unit) else failure }
                }
            }
        try {
            assertSame(
                failure.failure,
                assertIs<AppResult.Failed>(safeApiCall { client.get("resource") }).failure,
            )
            assertEquals(0, calls)
            permitted = true
            assertIs<AppResult.Success<*>>(safeApiCall { client.get("resource") })
            assertEquals(1, calls)
        } finally {
            client.close()
        }
    }
}
