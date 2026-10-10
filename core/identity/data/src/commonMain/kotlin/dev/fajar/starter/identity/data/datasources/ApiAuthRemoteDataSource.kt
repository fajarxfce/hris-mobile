package dev.fajar.starter.identity.data.datasources

import dev.fajar.starter.identity.data.api.AuthApi
import dev.fajar.starter.identity.data.dto.RefreshRequest
import dev.fajar.starter.identity.data.dto.SignInRequest
import org.koin.core.annotation.Single

@Single
class ApiAuthRemoteDataSource(private val api: AuthApi) : AuthRemoteDataSource {
    override suspend fun refresh(request: RefreshRequest, operationId: String) =
        api.refresh(request, operationId)

    override suspend fun signIn(request: SignInRequest, operationId: String) =
        api.signIn(request, operationId)
}
