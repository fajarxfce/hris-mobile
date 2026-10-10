package dev.fajar.starter.identity.data.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.identity.data.datasources.AuthRemoteDataSource
import dev.fajar.starter.identity.data.datasources.SessionDataSource
import dev.fajar.starter.identity.data.dto.SignInRequest
import dev.fajar.starter.identity.data.mappers.toUser
import dev.fajar.starter.identity.domain.repositories.IdentityRepository
import dev.fajar.starter.network.safeApiCall
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.map
import org.koin.core.annotation.Single

@Single
class DefaultIdentityRepository(
    private val remote: AuthRemoteDataSource,
    private val session: SessionDataSource,
) : IdentityRepository {
    override fun observeUser() = session.user.map { it?.toUser() }

    override suspend fun signIn(email: String, password: String) = safeApiCall {
        val dto = remote.signIn(SignInRequest(email, password))
        val user = dto.toUser()
        currentCoroutineContext().ensureActive()
        session.write(dto)
        user
    }

    override suspend fun signOut(): AppResult<Unit> {
        session.write(null)
        return AppResult.Success(Unit)
    }
}
