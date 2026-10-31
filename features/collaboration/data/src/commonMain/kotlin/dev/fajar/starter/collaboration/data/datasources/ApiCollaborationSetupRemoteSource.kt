package dev.fajar.starter.collaboration.data.datasources

import dev.fajar.starter.collaboration.data.api.CollaborationSetupApi
import dev.fajar.starter.collaboration.data.dto.*
import org.koin.core.annotation.Single

@Single
class ApiCollaborationSetupRemoteSource(private val api: CollaborationSetupApi) :
    CollaborationSetupRemoteSource {
    override suspend fun read(sessionId: String, companyId: String) = api.read(sessionId, companyId)

    override suspend fun register(
        sessionId: String,
        companyId: String,
        operation: String,
        input: RegisterCollaborationDeviceRequest,
    ) = api.register(sessionId, companyId, operation, input)

    override suspend fun retry(
        sessionId: String,
        companyId: String,
        operation: String,
        input: RetryCollaborationDeviceRequest,
    ) = api.retry(sessionId, companyId, operation, input)

    override suspend fun bootstrap(
        sessionId: String,
        companyId: String,
        operation: String,
        input: BootstrapCollaborationDeviceRequest,
    ) = api.bootstrap(sessionId, companyId, operation, input)
}
