@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.identity.domain

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.*
import dev.fajar.starter.identity.domain.repositories.*
import dev.fajar.starter.identity.domain.sso.entities.SsoProof
import dev.fajar.starter.identity.domain.usecases.AcquireSessionTokens
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class RefreshRecoveryTest {
    private class Remote(val action: suspend (Session, String) -> AppResult<AuthenticatedUser>) :
        IdentityRepository {
        override suspend fun signIn(email: String, password: String): AppResult<AuthenticatedUser> =
            error("unused")

        override suspend fun completeSso(proof: SsoProof): AppResult<AuthenticatedUser> =
            error("unused")

        override suspend fun refresh(session: Session, operationId: String) =
            action(session, operationId)
    }

    @Test
    fun lostResponseReusesDurableOperationAndOriginalTokenAfterOwnerRestart() = runTest {
        val original = testSession()
        val sessions = TestSessions(original)
        val attempts = mutableListOf<Pair<String, String>>()
        val remote = Remote { session, operation ->
            assertEquals(
                operation,
                assertIs<AppResult.Success<Session?>>(sessions.current())
                    .value
                    ?.pendingRefreshOperationId,
            )
            attempts += operation to session.tokens.refreshToken
            if (attempts.size == 1) AppResult.Failed(Failure(FailureKind.Network, "Offline"))
            else
                AppResult.Success(
                    AuthenticatedUser(
                        session.user,
                        session.tokens.copy(accessToken = "renewed", refreshToken = "rotated"),
                    )
                )
        }
        assertIs<AppResult.Failed>(AcquireSessionTokens(remote, sessions)(original.id, "access"))
        val afterRestart = AcquireSessionTokens(remote, sessions)
        assertEquals(
            "renewed",
            assertIs<AppResult.Success<SessionTokens?>>(afterRestart(original.id))
                .value
                ?.accessToken,
        )
        assertEquals(2, attempts.size)
        assertEquals(attempts[0], attempts[1])
        assertNull(
            assertIs<AppResult.Success<Session?>>(sessions.current())
                .value
                ?.pendingRefreshOperationId
        )
        afterRestart(original.id, "renewed")
        assertNotEquals(attempts[0].first, attempts[2].first)
        assertEquals("rotated", attempts[2].second)
    }

    @Test
    fun cancellationDuringRefreshRetainsIntentWithoutPublishingLateCredentials() = runTest {
        val original = testSession()
        val sessions = TestSessions(original)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var observedOperation: String? = null
        val remote = Remote { session, operation ->
            observedOperation = operation
            started.complete(Unit)
            withContext(NonCancellable) { release.await() }
            AppResult.Success(
                AuthenticatedUser(session.user, session.tokens.copy(accessToken = "late"))
            )
        }
        val job = async { AcquireSessionTokens(remote, sessions)(original.id, "access") }
        started.await()
        job.cancel()
        release.complete(Unit)
        assertFailsWith<CancellationException> { job.await() }
        val retained = assertIs<AppResult.Success<Session?>>(sessions.current()).value!!
        assertEquals("access", retained.tokens.accessToken)
        assertEquals(observedOperation, retained.pendingRefreshOperationId)
    }

    @Test
    fun noNetworkRotationStartsWhenItsIntentCannotBePersisted() = runTest {
        val original = testSession()
        val memory = TestSessions(original)
        val storageFailure = AppResult.Failed(Failure(FailureKind.Storage, "Unavailable"))
        val sessions =
            object : SessionRepository by memory {
                override suspend fun compareAndSet(
                    expected: Session?,
                    updated: Session?,
                ): AppResult<Boolean> = storageFailure
            }
        var called = false
        val remote = Remote { _, _ ->
            called = true
            error("must not rotate")
        }
        assertSame(storageFailure, AcquireSessionTokens(remote, sessions)(original.id, "access"))
        assertFalse(called)
        assertEquals(AppResult.Success(original), memory.current())
    }

    @Test
    fun aDifferentAccountCannotCommitARefreshFromThePreviousScope() = runTest {
        val original = testSession()
        val sessions = TestSessions(original)
        val remote = Remote { session, _ ->
            AppResult.Success(
                AuthenticatedUser(
                    session.user.copy(id = "another-account"),
                    session.tokens.copy(accessToken = "invalid"),
                )
            )
        }
        assertIs<AppResult.Failed>(AcquireSessionTokens(remote, sessions)(original.id, "access"))
        val retained = assertIs<AppResult.Success<Session?>>(sessions.current()).value!!
        assertEquals(original.tokens, retained.tokens)
        assertEquals(original.user, retained.user)
    }
}
