package dev.fajar.starter.identity.domain

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.common.result.FailureKind
import dev.fajar.starter.identity.domain.entities.User
import dev.fajar.starter.identity.domain.repositories.IdentityRepository
import dev.fajar.starter.identity.domain.usecases.SignIn
import kotlin.test.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

class SignInTest {
    private class Repository : IdentityRepository {
        var calls = 0
        var receivedEmail = ""
        var result: AppResult<User> = AppResult.Success(User("1", "Demo", "demo@example.com"))

        override fun observeUser() = flowOf<User?>(null)

        override suspend fun signIn(email: String, password: String): AppResult<User> {
            calls++
            receivedEmail = email
            return result
        }

        override suspend fun signOut() = AppResult.Success(Unit)
    }

    @Test
    fun invalidEmailDoesNotReachRepository() = runTest {
        val repository = Repository()
        val result = SignIn(repository)("invalid", "password")
        assertEquals("email", assertIs<AppResult.Failed>(result).failure.field)
        assertEquals(0, repository.calls)
    }

    @Test
    fun emptyPasswordDoesNotReachRepository() = runTest {
        val repository = Repository()
        val result = SignIn(repository)("demo@example.com", "")
        assertEquals("password", assertIs<AppResult.Failed>(result).failure.field)
        assertEquals(0, repository.calls)
    }

    @Test
    fun normalizesEmailAndPreservesRepositoryFailure() = runTest {
        val repository = Repository()
        val failure = AppResult.Failed(Failure(FailureKind.Network, "Offline"))
        repository.result = failure
        assertSame(failure, SignIn(repository)(" DEMO@EXAMPLE.COM ", "password"))
        assertEquals("demo@example.com", repository.receivedEmail)
    }
}
