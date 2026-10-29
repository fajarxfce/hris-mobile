@file:OptIn(
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    kotlin.io.encoding.ExperimentalEncodingApi::class,
)

package dev.fajar.starter.identity.data

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.identity.data.firebase.datasources.*
import dev.fajar.starter.identity.data.firebase.dto.*
import dev.fajar.starter.identity.data.firebase.repositories.InstalledFirebaseAuthenticationRepository
import dev.fajar.starter.identity.domain.firebase.entities.*
import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class GoogleAuthorizationExchangeTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun before() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun after() {
        Dispatchers.resetMain()
    }

    @Test
    fun theBrowserClosesBeforeGoogleAndFirebaseExchangesAndOnlyFirebaseProofIsReturned() = runTest {
        val fixture = Fixture()
        val result =
            assertIs<AppResult.Success<FirebaseSignInProof>>(
                fixture.repository.authenticate(FirebaseSignInInput.Google)
            )
        assertEquals(fixture.firebaseProof, result.value.idToken)
        assertEquals(listOf("browser-closed", "google-code", "firebase"), fixture.events)
    }

    @Test
    fun wrongGoogleNonceOrAudienceCannotReachFirebase() = runTest {
        for (wrongAudience in listOf(false, true)) {
            val fixture = Fixture()
            fixture.exchange = {
                fixture.token(
                    if (wrongAudience) "another-client" else fixture.clientId,
                    if (wrongAudience) fixture.nonce else "another-attempt",
                )
            }
            assertIs<AppResult.Failed>(fixture.repository.authenticate(FirebaseSignInInput.Google))
            assertEquals(listOf("browser-closed", "google-code"), fixture.events)
        }
    }

    @Test
    fun aCodeResultArrivingAfterCancellationCannotStartFirebaseOrPublishProof() = runTest {
        val fixture = Fixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.exchange = {
            withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
                fixture.token()
            }
        }
        val pending = async { fixture.repository.authenticate(FirebaseSignInInput.Google) }
        entered.await()
        pending.cancel()
        release.complete(Unit)
        pending.join()
        assertTrue(pending.isCancelled)
        assertEquals(listOf("browser-closed", "google-code"), fixture.events)
    }

    private class Fixture {
        val clientId = "123-ios.apps.googleusercontent.com"
        val nonce = "n".repeat(43)
        val firebaseProof = "firebase-proof".repeat(5)
        val events = mutableListOf<String>()
        var exchange: suspend () -> String = { token() }
        private val credential =
            GoogleCredentialResult.AuthorizationCode(
                "code",
                "v".repeat(43),
                nonce,
                "com.googleusercontent.apps.123-ios:/oauthredirect",
            )
        private val google =
            object : GoogleCredentialSource {
                override val supported = true

                override suspend fun open(clientId: String) =
                    object : GoogleCredentialSession {
                        override suspend fun credential() = credential

                        override fun close() {
                            events += "browser-closed"
                        }
                    }
            }
        private val remote =
            object : FirebaseProviderRemoteSource {
                override suspend fun password(
                    options: FirebaseClientOptions,
                    request: FirebasePasswordRequest,
                ): FirebaseProviderResponse = error("Unused")

                override suspend fun googleCode(
                    clientId: String,
                    credential: GoogleCredentialResult.AuthorizationCode,
                ): String {
                    assertEquals(listOf("browser-closed"), events)
                    assertEquals(this@Fixture.clientId, clientId)
                    assertEquals(this@Fixture.credential, credential)
                    events += "google-code"
                    return exchange()
                }

                override suspend fun google(
                    options: FirebaseClientOptions,
                    idToken: String,
                ): FirebaseProviderResponse {
                    assertEquals(token(), idToken)
                    events += "firebase"
                    return FirebaseProviderResponse(firebaseProof)
                }
            }
        val repository =
            InstalledFirebaseAuthenticationRepository(
                FirebaseClientOptionsSource {
                    FirebaseClientOptions(
                        "fixture-project",
                        "A".repeat(39),
                        clientId,
                        iosBundleId = "dev.fixture",
                    )
                },
                google,
                lazyOf(remote),
            )

        fun token(audience: String = clientId, actualNonce: String = nonce): String {
            val encoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
            return encoder.encode("""{"alg":"RS256"}""".encodeToByteArray()) +
                "." +
                encoder.encode(
                    """{"aud":"$audience","nonce":"$actualNonce"}""".encodeToByteArray()
                ) +
                ".fixture"
        }
    }
}
