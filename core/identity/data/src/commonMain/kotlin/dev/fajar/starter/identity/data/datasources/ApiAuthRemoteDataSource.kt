package dev.fajar.starter.identity.data.datasources

import dev.fajar.starter.identity.data.api.AuthApi
import dev.fajar.starter.identity.data.dto.OidcExchangeRequest
import dev.fajar.starter.identity.data.dto.RefreshRequest
import dev.fajar.starter.identity.data.dto.SignInRequest
import org.koin.core.annotation.Single

@Single
class ApiAuthRemoteDataSource(private val api: AuthApi) : AuthRemoteDataSource {
    override suspend fun completeSso(request: OidcExchangeRequest) = api.completeSso(request)

    override suspend fun refresh(request: RefreshRequest) = api.refresh(request)

    override suspend fun signIn(request: SignInRequest) = api.signIn(request)
}
