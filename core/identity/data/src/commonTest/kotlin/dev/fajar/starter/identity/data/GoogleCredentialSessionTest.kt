@file:OptIn(
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    kotlin.io.encoding.ExperimentalEncodingApi::class,
)

package dev.fajar.starter.identity.data

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.data.firebase.boundary.*
import dev.fajar.starter.identity.data.firebase.datasources.*
import dev.fajar.starter.identity.data.firebase.mappers.requireGoogleCredentialNonce
import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class GoogleCredentialSessionTest {
    @Test
    fun eachAttemptDisposesOnceAndRejectsLateCallbacksAndReuse() = runTest {
        lateinit var callback: GoogleCredentialCallback
        var cancelled = 0
        var closed = 0
        val session =
            CallbackGoogleCredentialSession(
                {
                    callback = it
                    AutoCloseable { cancelled++ }
                },
                { closed++ },
            )
        val pending = async { session.use { it.idToken() } }
        runCurrent()
        callback.success("first-token")
        callback.success("second-token")
        assertEquals("first-token", pending.await())
        callback.failure(GoogleCredentialUnavailable())
        session.close()
        assertEquals(1, cancelled)
        assertEquals(1, closed)
        assertFailsWith<IllegalStateException> { session.idToken() }
    }

    @Test
    fun ownerCancellationReleasesTheSdkBeforeALateProviderResult() = runTest {
        lateinit var callback: GoogleCredentialCallback
        var cancelled = false
        val session =
            CallbackGoogleCredentialSession(
                {
                    callback = it
                    AutoCloseable { cancelled = true }
                },
                {},
            )
        val pending = async { session.use { it.idToken() } }
        runCurrent()
        pending.cancelAndJoin()
        callback.success("late-token")
        assertTrue(cancelled)
        assertTrue(pending.isCancelled)
    }

    @Test
    fun acquisitionFailureReentrantCloseAndFailingCleanupKeepTheOwnerReleased() = runTest {
        var closed = 0
        val failed =
            CallbackGoogleCredentialSession({ throw GoogleCredentialUnavailable() }, { closed++ })
        assertFailsWith<GoogleCredentialUnavailable> { failed.use { it.idToken() } }
        assertEquals(1, closed)
        var cancelled = 0
        lateinit var reentrant: CallbackGoogleCredentialSession
        reentrant =
            CallbackGoogleCredentialSession(
                {
                    reentrant.close()
                    AutoCloseable { cancelled++ }
                },
                { closed++ },
            )
        assertFailsWith<CancellationException> { reentrant.use { it.idToken() } }
        assertEquals(1, cancelled)
        assertEquals(2, closed)
        val failingClose =
            CallbackGoogleCredentialSession(
                { callback ->
                    callback.success("token")
                    AutoCloseable { error("close failed") }
                },
                { closed++ },
            )
        assertFailsWith<IllegalStateException> { failingClose.use { it.idToken() } }
        assertEquals(3, closed)
    }

    @Test
    fun providerDismissalIsDistinctFromCoroutineCancellation() = runTest {
        val dismissed = safeFirebaseAuthenticationCall { throw GoogleCredentialCancelled() }
        assertEquals(FailureKind.Cancelled, assertIs<AppResult.Failed>(dismissed).failure.kind)
        assertFailsWith<CancellationException> {
            safeFirebaseAuthenticationCall {
                throw GoogleCredentialUnavailable(CancellationException("cancelled"))
            }
        }
    }

    @Test
    fun callbackNonceAndAudienceMustMatchThePlatformRequest() {
        fun encoded(value: String) =
            Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
                .encode(value.encodeToByteArray())
        fun token(claims: String) =
            encoded("""{"alg":"RS256"}""") + "." + encoded(claims) + ".fixture"
        val valid = token("""{"nonce":"owned-nonce","aud":"client-id"}""")
        requireGoogleCredentialNonce(valid, "client-id", "owned-nonce")
        assertFailsWith<IllegalArgumentException> {
            requireGoogleCredentialNonce(valid, "other-client", "owned-nonce")
        }
        assertFailsWith<IllegalArgumentException> {
            requireGoogleCredentialNonce(valid, "client-id", "other-nonce")
        }
        assertFailsWith<IllegalArgumentException> {
            requireGoogleCredentialNonce("x".repeat(8193), "client-id", "owned-nonce")
        }
        assertFailsWith<IllegalArgumentException> {
            requireGoogleCredentialNonce(
                token("""{"aud":"client-id"}"""),
                "client-id",
                "owned-nonce",
            )
        }
    }
}
