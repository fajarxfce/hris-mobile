package dev.fajar.starter.workspace.data.datasources

import dev.fajar.starter.workspace.data.dto.CompanyAccessDto

interface WorkspaceRemoteDataSource {
    suspend fun permissions(sessionId: String, companyId: String): CompanyAccessDto
}
