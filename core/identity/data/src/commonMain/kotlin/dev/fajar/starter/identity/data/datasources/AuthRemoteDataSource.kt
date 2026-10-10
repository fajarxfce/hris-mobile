package dev.fajar.starter.identity.data.datasources

import dev.fajar.starter.identity.data.dto.SignInRequest
import dev.fajar.starter.identity.data.dto.UserDto

interface AuthRemoteDataSource {
    suspend fun signIn(request: SignInRequest): UserDto
}
