package dev.fajar.starter.collaboration.data.datasources

import dev.fajar.starter.collaboration.data.dto.*

interface CollaborationSetupRemoteSource {
    suspend fun read(sessionId: String, companyId: String): CollaborationSetupDto

    suspend fun register(
        sessionId: String,
        companyId: String,
        operation: String,
        input: RegisterCollaborationDeviceRequest,
    ): CollaborationReceiptDto

    suspend fun retry(
        sessionId: String,
        companyId: String,
        operation: String,
        input: RetryCollaborationDeviceRequest,
    ): CollaborationReceiptDto

    suspend fun bootstrap(
        sessionId: String,
        companyId: String,
        operation: String,
        input: BootstrapCollaborationDeviceRequest,
    ): CollaborationGatewayDto
}
