@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.identity.data

import dev.fajar.starter.identity.data.firebase.datasources.*
import dev.fajar.starter.identity.data.firebase.dto.*
import dev.fajar.starter.identity.data.firebase.mappers.decodeGoogleAuthorizationCallback
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class AppleGoogleCredentialTest {
    @Test
    fun cancellationDisposesTheAttemptAndAnExplicitRetryGetsIndependentPkceAndCallbacks() =
        runTest {
            val requests = mutableListOf<GoogleAuthorizationRequest>()
            val callbacks = mutableListOf<GoogleCredentialCallback>()
            var closed = 0
            val source = AppleGoogleCredentialSource { request, callback ->
                requests += request
                callbacks += callback
                AutoCloseable { closed++ }
            }
            val first = source.open("123-ios.apps.googleusercontent.com")
            val pending = async { first.use { it.credential() } }
            runCurrent()
            assertFailsWith<IllegalStateException> {
                source.open("123-ios.apps.googleusercontent.com")
            }
            pending.cancelAndJoin()
            assertEquals(1, closed)
            callbacks[0].success(GoogleCredentialResult.IdToken("late-result"))
            val second = source.open("123-ios.apps.googleusercontent.com")
            val retry = async { second.use { it.credential() } }
            runCurrent()
            assertNotEquals(requests[0].verifier, requests[1].verifier)
            assertNotEquals(requests[0].state, requests[1].state)
            assertNotEquals(requests[0].nonce, requests[1].nonce)
            val request = requests[1]
            callbacks[1].success(
                decodeGoogleAuthorizationCallback(
                    "${request.redirectUri}?code=owned-code&state=${request.state}",
                    request,
                )
            )
            assertEquals(
                "owned-code",
                assertIs<GoogleCredentialResult.AuthorizationCode>(retry.await()).code,
            )
            assertEquals(2, closed)
            source.close()
            source.close()
            assertFailsWith<IllegalStateException> {
                source.open("123-ios.apps.googleusercontent.com")
            }
        }

    @Test
    fun containerDisposalCancelsPendingUiAndRejectsItsLateCallback() = runTest {
        lateinit var callback: GoogleCredentialCallback
        var closed = 0
        val source = AppleGoogleCredentialSource { _, result ->
            callback = result
            AutoCloseable { closed++ }
        }
        val session = source.open("123-ios.apps.googleusercontent.com")
        val pending = async { session.use { it.credential() } }
        runCurrent()
        source.close()
        callback.success(GoogleCredentialResult.IdToken("late-result"))
        assertFailsWith<CancellationException> { pending.await() }
        assertEquals(1, closed)
    }
}
