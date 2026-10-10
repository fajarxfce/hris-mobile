package dev.fajar.starter.identity.data.datasources

import dev.fajar.starter.identity.data.dto.*

interface AuthRemoteDataSource {
    suspend fun signIn(request: SignInRequest, operationId: String): AuthResponse

    suspend fun refresh(request: RefreshRequest, operationId: String): NativeCredentialsDto
}
