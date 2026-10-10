@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.identity.domain

import dev.fajar.starter.common.result.*
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.identity.domain.entities.*
import dev.fajar.starter.identity.domain.entities.AuthenticatedUser
import dev.fajar.starter.identity.domain.repositories.IdentityRepository
import dev.fajar.starter.identity.domain.sso.entities.SsoProof
import dev.fajar.starter.identity.domain.usecases.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class SessionPolicyTest {
    private class Remote(val action: suspend () -> AppResult<AuthenticatedUser>) :
        IdentityRepository {
        override suspend fun completeSso(proof: SsoProof): AppResult<AuthenticatedUser> =
            error("Unused SSO exchange")

        override suspend fun signIn(email: String, password: String) = action()

        override suspend fun refresh(refreshToken: String) = action()
    }

    @Test
    fun simultaneous401sRefreshOnceAndRejectRequestsFromAnOlderSession() = runTest {
        val session = testSession()
        val sessions = TestSessions(session)
        var calls = 0
        val pending = CompletableDeferred<Unit>()
        val acquire =
            AcquireSessionTokens(
                Remote {
                    calls++
                    pending.await()
                    AppResult.Success(
                        AuthenticatedUser(session.user, session.tokens.copy(accessToken = "new"))
                    )
                },
                sessions,
            )
        val results = List(12) { async { acquire(session.id, "access") } }
        runCurrent()
        assertEquals(1, calls)
        pending.complete(Unit)
        results.forEach {
            assertEquals(
                "new",
                assertIs<AppResult.Success<SessionTokens?>>(it.await()).value?.accessToken,
            )
        }
        assertEquals(1, calls)
        assertNull(assertIs<AppResult.Success<SessionTokens?>>(acquire("old-session")).value)
    }

    @Test
    fun lateRefreshNeverResurrectsASignedOutAccount() = runTest {
        val session = testSession()
        val sessions = TestSessions(session)
        val pending = CompletableDeferred<Unit>()
        val acquire =
            AcquireSessionTokens(
                Remote {
                    pending.await()
                    AppResult.Success(
                        AuthenticatedUser(session.user, session.tokens.copy(accessToken = "new"))
                    )
                },
                sessions,
            )
        val request = async { acquire(session.id, "access") }
        runCurrent()
        SignOut(sessions)()
        sessions.compareAndSet(null, session.copy(id = "another-login"))
        pending.complete(Unit)
        assertNull(assertIs<AppResult.Success<SessionTokens?>>(request.await()).value)
        assertEquals(
            "another-login",
            assertIs<AppResult.Success<Session?>>(sessions.current()).value?.id,
        )
    }

    @Test
    fun temporaryFailuresKeepSessionButRevokedRefreshClearsIt() = runTest {
        val session = testSession()
        val sessions = TestSessions(session)
        var failure = AppResult.Failed(Failure(FailureKind.Network, "Offline"))
        val acquire = AcquireSessionTokens(Remote { failure }, sessions)
        assertSame(failure, acquire(session.id, "access"))
        assertEquals(session, assertIs<AppResult.Success<Session?>>(sessions.current()).value)
        failure = AppResult.Failed(Failure(FailureKind.Unauthorized, "Expired"))
        assertSame(failure, acquire(session.id, "access"))
        assertNull(assertIs<AppResult.Success<Session?>>(sessions.current()).value)
    }

    @Test
    fun cancellationRejectsLateSignInAndRefreshBeforePersistence() = runTest {
        val session = testSession()
        val sessions = TestSessions()
        val pending = CompletableDeferred<Unit>()
        val remote = Remote {
            withContext(NonCancellable) { pending.await() }
            AppResult.Success(AuthenticatedUser(session.user, session.tokens))
        }
        val request = async { SignIn(remote, sessions)("demo@example.com", "password") }
        runCurrent()
        request.cancel()
        pending.complete(Unit)
        assertFailsWith<CancellationException> { request.await() }
        assertNull(assertIs<AppResult.Success<Session?>>(sessions.current()).value)
        sessions.compareAndSet(null, session)
        val refresh = async { AcquireSessionTokens(remote, sessions)(session.id, "access") }
        refresh.cancel()
        assertFailsWith<CancellationException> { refresh.await() }
        assertEquals(session, assertIs<AppResult.Success<Session?>>(sessions.current()).value)
    }
}
