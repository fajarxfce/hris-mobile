package dev.fajar.starter.collaboration.data.datasources

import dev.fajar.starter.collaboration.data.dto.*

interface CollaborationRecoveryRemoteSource {
    suspend fun devices(
        sessionId: String,
        afterCompanyId: String?,
        limit: Int,
    ): CollaborationDevicePageDto

    suspend fun admission(sessionId: String, companyId: String): CollaborationAdmissionDto
}
