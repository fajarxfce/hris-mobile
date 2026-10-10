@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.identity.domain.sso

import dev.fajar.starter.common.config.AppPlatform
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.*
import dev.fajar.starter.identity.domain.repositories.*
import dev.fajar.starter.identity.domain.sso.entities.*
import dev.fajar.starter.identity.domain.sso.repositories.SsoRepository
import dev.fajar.starter.identity.domain.sso.usecases.SignInWithSso
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*

class SignInWithSsoTest {
    @Test
    fun unknownProviderIsRejectedBeforeOpeningTheBrowser() = runTest {
        val sso = SsoFixture()
        val result = SignInWithSso(sso, ExchangeFixture(), SessionFixture())("missing")
        assertEquals(FailureKind.Validation, assertIs<AppResult.Failed>(result).failure.kind)
        assertEquals(0, sso.calls)
    }

    @Test
    fun aNewSessionDuringAuthorizationPreventsTheBackendExchange() = runTest {
        val pending = CompletableDeferred<Unit>()
        val sso = SsoFixture {
            pending.await()
            AppResult.Success(proof)
        }
        val identity = ExchangeFixture()
        val sessions = SessionFixture()
        val job = async { SignInWithSso(sso, identity, sessions)("organization") }
        runCurrent()
        sessions.value.value = AppResult.Success(Session("new-session", user.user, user.tokens))
        pending.complete(Unit)
        assertEquals(FailureKind.Unavailable, assertIs<AppResult.Failed>(job.await()).failure.kind)
        assertEquals(0, identity.calls)
    }

    @Test
    fun timeoutCancelsInteractiveWorkAndPreservesTheMissingSession() = runTest {
        var closed = false
        val sso = SsoFixture {
            try {
                awaitCancellation()
            } finally {
                closed = true
            }
        }
        val sessions = SessionFixture()
        val result =
            SignInWithSso(sso, ExchangeFixture(), sessions, timeoutMillis = 50)("organization")
        assertTrue(closed)
        assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(result).failure.kind)
        assertEquals(0, sessions.writes)
    }

    @Test
    fun cancelledBackendCompletionCannotPersistAnAuthenticatedSession() = runTest {
        val pending = CompletableDeferred<Unit>()
        val identity = ExchangeFixture {
            withContext(NonCancellable) { pending.await() }
            AppResult.Success(user)
        }
        val sessions = SessionFixture()
        val job = async { SignInWithSso(SsoFixture(), identity, sessions)("organization") }
        runCurrent()
        assertEquals(1, identity.calls)
        job.cancel()
        pending.complete(Unit)
        assertFailsWith<CancellationException> { job.await() }
        assertEquals(0, sessions.writes)
    }

    @Test
    fun concurrentSubmissionsCannotStartAnotherBrowserAfterSignIn() = runTest {
        val pending = CompletableDeferred<Unit>()
        val sso = SsoFixture {
            pending.await()
            AppResult.Success(proof)
        }
        val useCase = SignInWithSso(sso, ExchangeFixture(), SessionFixture())
        val first = async { useCase("organization") }
        val second = async { useCase("organization") }
        runCurrent()
        pending.complete(Unit)
        assertIs<AppResult.Success<User>>(first.await())
        assertIs<AppResult.Failed>(second.await())
        assertEquals(1, sso.calls)
    }
}

private val proof =
    SsoProof(
        "organization",
        AppPlatform.Desktop,
        "one-time-code",
        "v".repeat(43),
        "nonce",
        "http://127.0.0.1:48085/oauth/callback",
    )
private val user =
    AuthenticatedUser(
        User("u", "Demo", "demo@example.com"),
        SessionTokens("access", "refresh", Long.MAX_VALUE),
    )

private class SsoFixture(
    private val operation: suspend () -> AppResult<SsoProof> = { AppResult.Success(proof) }
) : SsoRepository {
    var calls = 0

    override suspend fun providers() =
        AppResult.Success(listOf(SsoProvider("organization", "Organization")))

    override suspend fun authorize(provider: SsoProvider): AppResult<SsoProof> {
        calls++
        return operation()
    }
}

private class ExchangeFixture(
    private val operation: suspend () -> AppResult<AuthenticatedUser> = { AppResult.Success(user) }
) : IdentityRepository {
    var calls = 0

    override suspend fun completeSso(proof: SsoProof): AppResult<AuthenticatedUser> {
        calls++
        return operation()
    }

    override suspend fun signIn(email: String, password: String): AppResult<AuthenticatedUser> =
        error("Unused")

    override suspend fun refresh(refreshToken: String): AppResult<AuthenticatedUser> =
        error("Unused")
}

private class SessionFixture : SessionRepository {
    override val persistent = false
    val value = MutableStateFlow<AppResult<Session?>>(AppResult.Success(null))
    var writes = 0

    override fun observe() = value

    override suspend fun current() = value.value

    override suspend fun compareAndSet(expected: Session?, updated: Session?): AppResult<Boolean> {
        writes++
        return AppResult.Success(
            value.compareAndSet(AppResult.Success(expected), AppResult.Success(updated))
        )
    }
}
