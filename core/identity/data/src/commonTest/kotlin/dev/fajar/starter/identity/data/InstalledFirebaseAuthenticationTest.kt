@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.identity.data

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.data.firebase.datasources.*
import dev.fajar.starter.identity.data.firebase.dto.*
import dev.fajar.starter.identity.data.firebase.repositories.InstalledFirebaseAuthenticationRepository
import dev.fajar.starter.identity.domain.firebase.entities.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class InstalledFirebaseAuthenticationTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun before() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun after() {
        Dispatchers.resetMain()
    }

    private val options =
        FirebaseClientOptions(
            "cmp-fajar",
            "A".repeat(39),
            "123456-abcdef.apps.googleusercontent.com",
        )
    private val proof = "firebase-proof".repeat(5)

    @Test
    fun missingConfigurationAndUnsupportedGoogleAcquireNoRemoteOrInteractiveResource() = runTest {
        var acquisitions = 0
        val remote =
            lazy<FirebaseProviderRemoteSource> {
                acquisitions++
                error("Unexpected acquisition")
            }
        val disabled =
            InstalledFirebaseAuthenticationRepository(
                FirebaseClientOptionsSource { null },
                UnavailableGoogleCredentialSource(),
                remote,
            )
        assertEquals(
            AppResult.Success(FirebaseSignInConfiguration(false, null, emptySet())),
            disabled.configuration(),
        )
        val supported =
            InstalledFirebaseAuthenticationRepository(
                FirebaseClientOptionsSource { options },
                UnavailableGoogleCredentialSource(),
                remote,
            )
        assertEquals(
            setOf(FirebaseSignInProvider.EmailPassword),
            assertIs<AppResult.Success<FirebaseSignInConfiguration>>(supported.configuration())
                .value
                .providers,
        )
        assertIs<AppResult.Failed>(supported.authenticate(FirebaseSignInInput.Google))
        assertEquals(0, acquisitions)
    }

    @Test
    fun googleInteractionIsDisposedBeforeFirebaseNetworkExchange() = runTest {
        var closed = false
        val google =
            object : GoogleCredentialSource {
                override val supported = true

                override suspend fun open(webClientId: String): GoogleCredentialSession {
                    assertEquals(options.googleWebClientId, webClientId)
                    return object : GoogleCredentialSession {
                        override suspend fun idToken() = "google-proof".repeat(5)

                        override fun close() {
                            closed = true
                        }
                    }
                }
            }
        val remote =
            object : FirebaseProviderRemoteSource {
                override suspend fun password(
                    options: FirebaseClientOptions,
                    request: FirebasePasswordRequest,
                ): FirebaseProviderResponse = error("Unused")

                override suspend fun google(
                    options: FirebaseClientOptions,
                    idToken: String,
                ): FirebaseProviderResponse {
                    assertTrue(closed)
                    assertEquals("google-proof".repeat(5), idToken)
                    return FirebaseProviderResponse(proof)
                }
            }
        val result =
            InstalledFirebaseAuthenticationRepository(
                    FirebaseClientOptionsSource { options },
                    google,
                    lazyOf(remote),
                )
                .authenticate(FirebaseSignInInput.Google)
        assertEquals(
            FirebaseSignInProof("cmp-fajar", FirebaseSignInProvider.Google, proof),
            assertIs<AppResult.Success<FirebaseSignInProof>>(result).value,
        )
    }

    @Test
    fun pendingGoogleCancellationDoesNotAcquireFirebaseTransport() = runTest {
        val entered = CompletableDeferred<Unit>()
        var closed = false
        var acquisitions = 0
        val google =
            object : GoogleCredentialSource {
                override val supported = true

                override suspend fun open(webClientId: String) =
                    object : GoogleCredentialSession {
                        override suspend fun idToken(): String {
                            entered.complete(Unit)
                            awaitCancellation()
                        }

                        override fun close() {
                            closed = true
                        }
                    }
            }
        val repository =
            InstalledFirebaseAuthenticationRepository(
                FirebaseClientOptionsSource { options },
                google,
                lazy {
                    acquisitions++
                    error("Unused")
                },
            )
        val pending = async { repository.authenticate(FirebaseSignInInput.Google) }
        entered.await()
        pending.cancelAndJoin()
        assertTrue(closed)
        assertEquals(0, acquisitions)
    }

    @Test
    fun accountLinkRequestsAndInvalidFirebaseProofNeverBecomeProviderSuccess() = runTest {
        for (response in
            listOf(
                FirebaseProviderResponse(null, true),
                FirebaseProviderResponse(null),
                FirebaseProviderResponse("x".repeat(8193)),
            )) {
            val remote =
                object : FirebaseProviderRemoteSource {
                    override suspend fun password(
                        options: FirebaseClientOptions,
                        request: FirebasePasswordRequest,
                    ) = response

                    override suspend fun google(
                        options: FirebaseClientOptions,
                        idToken: String,
                    ): FirebaseProviderResponse = error("Unused")
                }
            val repository =
                InstalledFirebaseAuthenticationRepository(
                    FirebaseClientOptionsSource { options },
                    UnavailableGoogleCredentialSource(),
                    lazyOf(remote),
                )
            assertIs<AppResult.Failed>(
                repository.authenticate(
                    FirebaseSignInInput.EmailPassword("fixture@example.test", "fixture-password")
                )
            )
        }
    }
}
