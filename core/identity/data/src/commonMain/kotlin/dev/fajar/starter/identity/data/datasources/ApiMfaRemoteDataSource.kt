package dev.fajar.starter.identity.data.datasources

import dev.fajar.starter.identity.data.api.MfaApi
import dev.fajar.starter.identity.data.dto.*
import org.koin.core.annotation.Single

@Single
class ApiMfaRemoteDataSource(private val api: MfaApi) : MfaRemoteDataSource {
    override suspend fun begin(accessToken: String, operationId: String) =
        api.begin(accessToken, operationId)

    override suspend fun confirm(
        accessToken: String,
        operationId: String,
        request: MfaConfirmationRequest,
    ) = api.confirm(accessToken, operationId, request)

    override suspend fun verify(
        accessToken: String,
        operationId: String,
        request: MfaVerificationRequest,
    ) = api.verify(accessToken, operationId, request)
}
