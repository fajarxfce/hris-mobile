package dev.fajar.starter.identity.data.datasources

import dev.fajar.starter.identity.data.dto.*

interface AuthRemoteDataSource {
    suspend fun completeSso(request: OidcExchangeRequest): AuthResponse

    suspend fun signIn(request: SignInRequest): AuthResponse

    suspend fun refresh(request: RefreshRequest): AuthResponse
}
