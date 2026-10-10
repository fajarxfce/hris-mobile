package dev.fajar.starter.identity.data

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.identity.data.datasources.AuthRemoteDataSource
import dev.fajar.starter.identity.data.datasources.MemorySessionDataSource
import dev.fajar.starter.identity.data.dto.SignInRequest
import dev.fajar.starter.identity.data.dto.UserDto
import dev.fajar.starter.identity.data.repositories.DefaultIdentityRepository
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class IdentityRepositoryTest {
    private val user = UserDto("1", "Alex Morgan", "demo@example.com")

    @Test
    fun signInPublishesAndSignOutClearsSession() = runTest {
        val session = MemorySessionDataSource()
        val remote =
            object : AuthRemoteDataSource {
                override suspend fun signIn(request: SignInRequest) = user
            }
        val repository = DefaultIdentityRepository(remote, session)
        assertNull(repository.observeUser().first())
        assertIs<AppResult.Success<*>>(repository.signIn(user.email, "Demo123!"))
        assertEquals(user.name, repository.observeUser().first()?.name)
        assertIs<AppResult.Success<*>>(repository.signOut())
        assertNull(repository.observeUser().first())
    }

    @Test
    fun cancelledLoginNeverPublishesALateSession() = runTest {
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<UserDto>()
        val session = MemorySessionDataSource()
        val remote =
            object : AuthRemoteDataSource {
                override suspend fun signIn(request: SignInRequest): UserDto {
                    started.complete(Unit)
                    return response.await()
                }
            }
        val repository = DefaultIdentityRepository(remote, session)
        val login = async { repository.signIn(user.email, "Demo123!") }
        started.await()
        login.cancelAndJoin()
        response.complete(user)
        assertNull(session.user.value)
    }

    @Test
    fun remoteExceptionDoesNotLeakOrChangeCurrentUser() = runTest {
        val session = MemorySessionDataSource()
        session.write(user)
        val remote =
            object : AuthRemoteDataSource {
                override suspend fun signIn(request: SignInRequest): UserDto =
                    error("private response")
            }
        val repository = DefaultIdentityRepository(remote, session)
        assertIs<AppResult.Failed>(repository.signIn(user.email, "wrong"))
        assertEquals(user, session.user.value)
    }
}
