@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.identity.domain.firebase

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.AuthenticatedUser
import dev.fajar.starter.identity.domain.firebase.entities.*
import dev.fajar.starter.identity.domain.firebase.repositories.*
import dev.fajar.starter.identity.domain.firebase.usecases.LoadFirebaseSignInOptions
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class LoadFirebaseSignInOptionsTest {
    private val enabled =
        FirebaseSignInConfiguration(true, "cmp-fajar", FirebaseSignInProvider.entries.toSet())

    private inner class Fixture {
        var local = enabled
        var remote = enabled
        var reads = 0
        var failure: AppResult.Failed? = null
        var read: suspend () -> Unit = {}
        val load =
            LoadFirebaseSignInOptions(
                object : FirebaseAuthenticationRepository {
                    override suspend fun configuration() = AppResult.Success(local)

                    override suspend fun authenticate(
                        input: FirebaseSignInInput
                    ): AppResult<FirebaseSignInProof> = error("Discovery must not authenticate")
                },
                object : NativeFirebaseSessionRepository {
                    override suspend fun configuration(): AppResult<FirebaseSignInConfiguration> {
                        reads++
                        read()
                        return failure ?: AppResult.Success(remote)
                    }

                    override suspend fun exchange(
                        idToken: String,
                        deviceName: String,
                        operationId: String,
                    ): AppResult<AuthenticatedUser> = error("Discovery must not exchange")
                },
            )
    }

    @Test
    fun absentClientConfigurationAndExplicitlyDisabledBackendKeepNativePassword() = runTest {
        val absent = Fixture().apply { local = enabled.copy(enabled = false) }
        assertEquals(AppResult.Success(FirebaseSignInOptions()), absent.load())
        assertEquals(0, absent.reads)
        val disabled = Fixture().apply { remote = enabled.copy(enabled = false) }
        assertEquals(AppResult.Success(FirebaseSignInOptions()), disabled.load())
        assertEquals(1, disabled.reads)
    }

    @Test
    fun matchingCapabilitiesSelectFirebaseWithoutInventingAnotherPasswordAuthority() = runTest {
        val f = Fixture()
        assertEquals(AppResult.Success(FirebaseSignInOptions(true, true, true)), f.load())
        f.remote = enabled.copy(providers = setOf(FirebaseSignInProvider.Google))
        assertEquals(AppResult.Success(FirebaseSignInOptions(false, true, false)), f.load())
        f.local = enabled.copy(providers = setOf(FirebaseSignInProvider.EmailPassword))
        assertIs<AppResult.Failed>(f.load())
    }

    @Test
    fun failedOrMismatchedDiscoveryCannotFallBackToNativePassword() = runTest {
        val f = Fixture().apply { remote = enabled.copy(projectId = "another-project") }
        assertIs<AppResult.Failed>(f.load())
        f.failure = AppResult.Failed(Failure(FailureKind.Network, "Offline"))
        assertSame(checkNotNull(f.failure), assertIs<AppResult.Failed>(f.load()))
    }

    @Test
    fun stalledDiscoveryIsBoundedAndCallerCancellationStaysCancellation() = runTest {
        val f = Fixture()
        var cancelled = 0
        f.read = {
            try {
                awaitCancellation()
            } finally {
                cancelled++
            }
        }
        val timed = async { f.load() }
        advanceUntilIdle()
        assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(timed.await()).failure.kind)
        assertEquals(1, cancelled)
        val pending = async { f.load() }
        runCurrent()
        pending.cancelAndJoin()
        assertTrue(pending.isCancelled)
        assertEquals(2, cancelled)
    }
}
