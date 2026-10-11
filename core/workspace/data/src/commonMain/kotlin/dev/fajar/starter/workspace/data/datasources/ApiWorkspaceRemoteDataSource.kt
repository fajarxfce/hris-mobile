package dev.fajar.starter.workspace.data.datasources

import dev.fajar.starter.workspace.data.api.WorkspaceApi
import org.koin.core.annotation.Single

@Single
class ApiWorkspaceRemoteDataSource(private val api: WorkspaceApi) : WorkspaceRemoteDataSource {
    override suspend fun permissions(sessionId: String, companyId: String) =
        api.permissions(sessionId, companyId)

    override suspend fun clientPolicy(sessionId: String, companyId: String) =
        api.clientPolicy(sessionId, companyId)
}
