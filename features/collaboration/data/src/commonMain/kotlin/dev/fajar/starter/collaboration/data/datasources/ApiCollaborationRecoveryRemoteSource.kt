package dev.fajar.starter.collaboration.data.datasources

import dev.fajar.starter.collaboration.data.api.CollaborationRecoveryApi
import org.koin.core.annotation.Single

@Single
class ApiCollaborationRecoveryRemoteSource(private val api: CollaborationRecoveryApi) :
    CollaborationRecoveryRemoteSource {
    override suspend fun devices(sessionId: String, afterCompanyId: String?, limit: Int) =
        api.devices(sessionId, afterCompanyId, limit)

    override suspend fun admission(sessionId: String, companyId: String) =
        api.admission(sessionId, companyId)
}
