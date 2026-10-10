@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.identity.domain

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.*
import dev.fajar.starter.identity.domain.repositories.*
import dev.fajar.starter.identity.domain.sso.entities.SsoProof
import dev.fajar.starter.identity.domain.usecases.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.*

class MfaConcurrencyTest {
    private val server = IdentityServer("https://example.invalid/", "Test Android")

    private class Mfa(val execute: suspend (Session) -> AppResult<AuthenticatedUser>) :
        MfaRepository {
        var calls = 0

        override suspend fun begin(
            accessToken: String,
            operationId: String,
        ): AppResult<MfaEnrollment> = error("Unused")

        override suspend fun confirm(
            session: Session,
            operationId: String,
            enrollmentId: String,
            code: String,
        ): AppResult<MfaConfirmation> = error("Unused")

        override suspend fun verify(
            session: Session,
            operationId: String,
            code: String,
            recovery: Boolean,
        ): AppResult<AuthenticatedUser> {
            calls++
            return execute(session)
        }
    }

    @Test
    fun refreshWaitsForMfaAndReusesItsRotatedCredential() = runTest {
        val initial = testSession()
        val sessions = TestSessions(initial)
        val release = CompletableDeferred<Unit>()
        val lock = Mutex()
        val mfa = Mfa { session ->
            release.await()
            AppResult.Success(
                AuthenticatedUser(
                    session.user,
                    session.tokens.copy(accessToken = "elevated"),
                    session.binding,
                )
            )
        }
        var refreshes = 0
        val remote =
            object : IdentityRepository {
                override suspend fun completeSso(proof: SsoProof): AppResult<AuthenticatedUser> =
                    error("Unused")

                override suspend fun signIn(
                    email: String,
                    password: String,
                    deviceName: String,
                    operationId: String,
                ): AppResult<AuthenticatedUser> = error("Unused")

                override suspend fun refresh(
                    session: Session,
                    operationId: String,
                ): AppResult<AuthenticatedUser> {
                    refreshes++
                    return AppResult.Success(
                        AuthenticatedUser(
                            session.user,
                            session.tokens.copy(accessToken = "refreshed"),
                            session.binding,
                        )
                    )
                }
            }
        val verify = async {
            VerifyMfa(sessions, mfa, server, lock)(initial.id, "operation", "123456", false)
        }
        runCurrent()
        val acquire = async {
            AcquireSessionTokens(remote, sessions, server, refresh = lock)(initial.id, "access")
        }
        runCurrent()
        assertEquals(1, mfa.calls)
        assertEquals(0, refreshes)
        assertFalse(acquire.isCompleted)
        release.complete(Unit)
        assertEquals(AppResult.Success(Unit), verify.await())
        assertEquals(
            "elevated",
            assertIs<AppResult.Success<SessionTokens?>>(acquire.await()).value?.accessToken,
        )
        assertEquals(0, refreshes)
    }

    @Test
    fun aLateProofCannotRestoreAReplacedAccountOrPublishAfterCancellation() = runTest {
        for (cancel in listOf(false, true)) {
            val initial = testSession()
            val sessions = TestSessions(initial)
            val release = CompletableDeferred<Unit>()
            val mfa = Mfa { session ->
                withContext(NonCancellable) { release.await() }
                AppResult.Success(
                    AuthenticatedUser(
                        session.user,
                        session.tokens.copy(accessToken = "late"),
                        session.binding,
                    )
                )
            }
            val request = async {
                VerifyMfa(sessions, mfa, server, Mutex())(initial.id, "operation", "123456", false)
            }
            runCurrent()
            if (cancel) request.cancel()
            else sessions.compareAndSet(initial, initial.copy(id = "replacement"))
            release.complete(Unit)
            if (cancel) assertFailsWith<CancellationException> { request.await() }
            else assertIs<AppResult.Failed>(request.await())
            val stored = assertIs<AppResult.Success<Session?>>(sessions.current()).value!!
            assertEquals("access", stored.tokens.accessToken)
            assertEquals(if (cancel) initial.id else "replacement", stored.id)
        }
    }

    @Test
    fun malformedCodesAndPendingRefreshesNeverReachTheProofEndpoint() = runTest {
        val initial = testSession()
        val sessions = TestSessions(initial)
        val mfa = Mfa { error("Proof must not be sent") }
        val verify = VerifyMfa(sessions, mfa, server, Mutex())
        assertIs<AppResult.Failed>(verify(initial.id, "operation", "bad", false))
        sessions.compareAndSet(initial, initial.copy(pendingRefreshOperationId = "prepared"))
        assertIs<AppResult.Failed>(verify(initial.id, "operation", "123456", false))
        assertEquals(0, mfa.calls)
    }
}
