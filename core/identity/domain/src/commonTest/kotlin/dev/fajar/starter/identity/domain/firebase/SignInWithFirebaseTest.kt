@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.identity.domain.firebase

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.TestSessions
import dev.fajar.starter.identity.domain.entities.*
import dev.fajar.starter.identity.domain.firebase.entities.*
import dev.fajar.starter.identity.domain.firebase.repositories.*
import dev.fajar.starter.identity.domain.firebase.usecases.SignInWithFirebase
import dev.fajar.starter.identity.domain.testSession
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class SignInWithFirebaseTest {
    private val operation = "22222222-2222-4222-8222-222222222222"
    private val password =
        FirebaseSignInInput.EmailPassword(" TEST@example.test ", "fixture-password")
    private val config =
        FirebaseSignInConfiguration(true, "cmp-fajar", FirebaseSignInProvider.entries.toSet())
    private val failure =
        AppResult.Failed(
            Failure(FailureKind.Service, "Unavailable", code = "firebase_sign_in_unavailable")
        )

    private inner class Fixture {
        val sessions = TestSessions()
        var local = config
        var remote = config
        var localReads = 0
        var remoteReads = 0
        var authorizations = 0
        var exchanges = 0
        var received: FirebaseSignInInput? = null
        var receivedOperation: String? = null
        var configurationHook: suspend () -> Unit = {}
        var authorizationHook: suspend () -> Unit = {}
        var exchangeHook: suspend () -> Unit = {}
        var proofProject = "cmp-fajar"
        var proofProvider: FirebaseSignInProvider? = null
        var proofToken = "fixture-id-token".repeat(4)
        var providerFailure: AppResult.Failed? = null
        var remoteFailure: AppResult.Failed? = null
        var authenticated = testSession().let { AuthenticatedUser(it.user, it.tokens, it.binding) }
        val useCase =
            SignInWithFirebase(
                object : FirebaseAuthenticationRepository {
                    override suspend fun configuration(): AppResult<FirebaseSignInConfiguration> {
                        localReads++
                        return AppResult.Success(local)
                    }

                    override suspend fun authenticate(
                        input: FirebaseSignInInput
                    ): AppResult<FirebaseSignInProof> {
                        authorizations++
                        received = input
                        authorizationHook()
                        return providerFailure
                            ?: AppResult.Success(
                                FirebaseSignInProof(
                                    proofProject,
                                    proofProvider ?: input.provider,
                                    proofToken,
                                )
                            )
                    }
                },
                object : NativeFirebaseSessionRepository {
                    override suspend fun configuration(): AppResult<FirebaseSignInConfiguration> {
                        remoteReads++
                        configurationHook()
                        return AppResult.Success(remote)
                    }

                    override suspend fun exchange(
                        idToken: String,
                        deviceName: String,
                        operationId: String,
                    ): AppResult<AuthenticatedUser> {
                        exchanges++
                        receivedOperation = operationId
                        assertEquals(proofToken, idToken)
                        assertEquals("Test device", deviceName)
                        exchangeHook()
                        return remoteFailure ?: AppResult.Success(authenticated)
                    }
                },
                sessions,
                IdentityServer(testSession().binding.audience, "Test device"),
                object : Clock {
                    override fun now() = Instant.parse("2026-10-28T00:00:00Z")
                },
                timeoutMillis = 1000,
            )
    }

    @Test
    fun bothProvidersPublishOnlyHrisCredentialsAndRetainTheOperationIdentity() = runTest {
        for (input in listOf(password, FirebaseSignInInput.Google)) {
            val f = Fixture()
            assertEquals(AppResult.Success(f.authenticated.user), f.useCase(input, operation))
            val session = assertIs<AppResult.Success<Session?>>(f.sessions.current()).value!!
            assertEquals(f.authenticated.tokens, session.tokens)
            assertEquals(f.authenticated.binding, session.binding)
            assertEquals(operation, f.receivedOperation)
            assertEquals(1, f.authorizations)
            assertEquals(1, f.exchanges)
            if (input is FirebaseSignInInput.EmailPassword)
                assertEquals(
                    "test@example.test",
                    assertIs<FirebaseSignInInput.EmailPassword>(f.received).email,
                )
            assertFalse(session.toString().contains(f.proofToken))
        }
        assertFalse(password.toString().contains("fixture-password"))
        assertFalse(
            FirebaseSignInProof("cmp-fajar", FirebaseSignInProvider.Google, "sensitive-proof")
                .toString()
                .contains("sensitive-proof")
        )
    }

    @Test
    fun disabledUnsupportedAndMismatchedProjectsNeverOpenTheProvider() = runTest {
        for (mode in 0..5) {
            val f = Fixture()
            when (mode) {
                0 -> f.local = config.copy(enabled = false)
                1 -> f.local = config.copy(providers = emptySet())
                2 -> f.local = config.copy(projectId = "../invalid")
                3 -> f.remote = config.copy(enabled = false)
                4 -> f.remote = config.copy(projectId = "another-project")
                5 -> f.remote = config.copy(providers = emptySet())
            }
            assertIs<AppResult.Failed>(f.useCase(password, operation))
            assertEquals(0, f.authorizations)
            assertEquals(0, f.exchanges)
            if (mode < 3) assertEquals(0, f.remoteReads)
        }
    }

    @Test
    fun invalidInputStopsBeforeConfigurationAcquisition() = runTest {
        for ((input, key) in
            listOf(
                FirebaseSignInInput.EmailPassword("invalid", "password") to operation,
                password.copy(password = "") to operation,
                password.copy(password = "x".repeat(4097)) to operation,
                password.copy(email = "x".repeat(255) + "@test.invalid") to operation,
                password to "invalid-operation",
            )) {
            val f = Fixture()
            assertIs<AppResult.Failed>(f.useCase(input, key))
            assertEquals(0, f.localReads)
            assertEquals(0, f.authorizations)
        }
    }

    @Test
    fun providerAndExchangeFailuresArePreservedWithoutAutomaticRetry() = runTest {
        val provider = Fixture().apply { providerFailure = failure }
        assertSame(failure, provider.useCase(password, operation))
        assertEquals(0, provider.exchanges)
        val remote = Fixture().apply { remoteFailure = failure }
        assertSame(failure, remote.useCase(password, operation))
        assertEquals(1, remote.exchanges)
        assertEquals(AppResult.Success(null), remote.sessions.current())
        remote.remoteFailure = null
        assertIs<AppResult.Success<User>>(remote.useCase(password, operation))
        assertEquals(operation, remote.receivedOperation)
        assertEquals(2, remote.exchanges)
    }

    @Test
    fun replacementSessionsAtEveryAcquisitionBoundaryRemainOwned() = runTest {
        for (stage in 0..3) {
            val f = Fixture()
            val replacement = testSession().copy(id = "replacement")
            val replace: suspend () -> Unit = {
                f.sessions.compareAndSet(null, replacement)
                Unit
            }
            when (stage) {
                0 -> replace()
                1 -> f.configurationHook = replace
                2 -> f.authorizationHook = replace
                3 -> f.exchangeHook = replace
            }
            assertIs<AppResult.Failed>(f.useCase(password, operation))
            assertEquals(AppResult.Success(replacement), f.sessions.current())
            if (stage < 2) assertEquals(0, f.authorizations)
            if (stage < 3) assertEquals(0, f.exchanges)
        }
    }

    @Test
    fun changedProofAndExpiredOrWrongAudienceSessionsAreRejected() = runTest {
        for (mode in 0..6) {
            val f = Fixture()
            when (mode) {
                0 -> f.proofProject = "another-project"
                1 -> f.proofProvider = FirebaseSignInProvider.Google
                2 -> f.proofToken = "x".repeat(8193)
                3 -> f.proofToken = "x ".repeat(32)
                4 ->
                    f.authenticated =
                        f.authenticated.copy(
                            binding =
                                f.authenticated.binding.copy(audience = "https://another.invalid/")
                        )
                5 ->
                    f.authenticated =
                        f.authenticated.copy(
                            binding = f.authenticated.binding.copy(expiresAtEpochMillis = 1)
                        )
                6 ->
                    f.authenticated =
                        f.authenticated.copy(
                            tokens = f.authenticated.tokens.copy(expiresAtEpochMillis = 1)
                        )
            }
            assertIs<AppResult.Failed>(f.useCase(password, operation))
            assertEquals(AppResult.Success(null), f.sessions.current())
            if (mode < 4) assertEquals(0, f.exchanges)
        }
    }

    @Test
    fun timeoutOwnsProviderCancellationAndNeverPublishesAResult() = runTest {
        val f = Fixture()
        var released = false
        f.authorizationHook = {
            try {
                awaitCancellation()
            } finally {
                released = true
            }
        }
        val pending = async { f.useCase(FirebaseSignInInput.Google, operation) }
        advanceUntilIdle()
        assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(pending.await()).failure.kind)
        assertTrue(released)
        assertEquals(0, f.exchanges)
        assertEquals(AppResult.Success(null), f.sessions.current())
    }

    @Test
    fun lateProviderOrBackendResultsCannotOutliveTheCaller() = runTest {
        for (provider in listOf(true, false)) {
            val f = Fixture()
            val release = CompletableDeferred<Unit>()
            val hook: suspend () -> Unit = { withContext(NonCancellable) { release.await() } }
            if (provider) f.authorizationHook = hook else f.exchangeHook = hook
            val pending = async { f.useCase(password, operation) }
            runCurrent()
            pending.cancel()
            release.complete(Unit)
            pending.join()
            assertTrue(pending.isCancelled)
            assertEquals(AppResult.Success(null), f.sessions.current())
            if (provider) assertEquals(0, f.exchanges)
        }
    }

    @Test
    fun competingInvocationsShareOneInteractiveOwner() = runTest {
        val f = Fixture()
        val release = CompletableDeferred<Unit>()
        f.authorizationHook = { release.await() }
        val first = async { f.useCase(password, operation) }
        runCurrent()
        val second = async { f.useCase(FirebaseSignInInput.Google, operation) }
        runCurrent()
        assertEquals(1, f.authorizations)
        release.complete(Unit)
        assertIs<AppResult.Success<User>>(first.await())
        assertIs<AppResult.Failed>(second.await())
        assertEquals(1, f.exchanges)
    }
}
