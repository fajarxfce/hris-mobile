package dev.fajar.starter.identity.domain.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.identity.domain.entities.User
import kotlinx.coroutines.flow.Flow

interface IdentityRepository {
    fun observeUser(): Flow<User?>

    suspend fun signIn(email: String, password: String): AppResult<User>

    suspend fun signOut(): AppResult<Unit>
}
