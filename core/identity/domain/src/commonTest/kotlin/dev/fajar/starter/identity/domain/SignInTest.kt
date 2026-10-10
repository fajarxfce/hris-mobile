package dev.fajar.starter.identity.domain

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.common.result.FailureKind
import dev.fajar.starter.identity.domain.entities.*
import dev.fajar.starter.identity.domain.entities.AuthenticatedUser
import dev.fajar.starter.identity.domain.repositories.IdentityRepository
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.identity.domain.sso.entities.SsoProof
import dev.fajar.starter.identity.domain.usecases.SignIn
import kotlin.test.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest

class SignInTest {
    private class Repository : IdentityRepository {
        override suspend fun completeSso(proof: SsoProof): AppResult<AuthenticatedUser> =
            error("Unused SSO exchange")

        var calls = 0
        var receivedEmail = ""
        var result: AppResult<AuthenticatedUser> =
            AppResult.Success(AuthenticatedUser(testSession().user, testSession().tokens))

        override suspend fun signIn(email: String, password: String): AppResult<AuthenticatedUser> {
            calls++
            receivedEmail = email
            return result
        }

        override suspend fun refresh(refreshToken: String): AppResult<AuthenticatedUser> =
            error("Unused")
    }

    @Test
    fun invalidEmailDoesNotReachRepository() = runTest {
        val repository = Repository()
        val result = SignIn(repository, TestSessions())("invalid", "password")
        assertEquals("email", assertIs<AppResult.Failed>(result).failure.field)
        assertEquals(0, repository.calls)
    }

    @Test
    fun emptyPasswordDoesNotReachRepository() = runTest {
        val repository = Repository()
        val result = SignIn(repository, TestSessions())("demo@example.com", "")
        assertEquals("password", assertIs<AppResult.Failed>(result).failure.field)
        assertEquals(0, repository.calls)
    }

    @Test
    fun normalizesEmailAndPreservesRepositoryFailure() = runTest {
        val repository = Repository()
        val failure = AppResult.Failed(Failure(FailureKind.Network, "Offline"))
        repository.result = failure
        assertSame(failure, SignIn(repository, TestSessions())(" DEMO@EXAMPLE.COM ", "password"))
        assertEquals("demo@example.com", repository.receivedEmail)
    }
}

internal class TestSessions(initial: Session? = null) : SessionRepository {
    override val persistent = false
    val value = MutableStateFlow<AppResult<Session?>>(AppResult.Success(initial))

    override fun observe() = value

    override suspend fun current() = value.value

    override suspend fun compareAndSet(expected: Session?, updated: Session?): AppResult<Boolean> {
        val current = value.value
        if (current is AppResult.Failed) return current
        if ((current as AppResult.Success).value != expected) return AppResult.Success(false)
        value.value = AppResult.Success(updated)
        return AppResult.Success(true)
    }
}

internal fun testSession() =
    Session(
        "session-a",
        User("1", "Alex", "demo@example.com"),
        SessionTokens("access", "refresh", Long.MAX_VALUE),
    )
