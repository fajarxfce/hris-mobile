package dev.fajar.starter.workspace.data.datasources

import dev.fajar.starter.workspace.data.dto.CompanyAccessDto
import dev.fajar.starter.workspace.data.dto.CompanyPolicyDto

interface WorkspaceRemoteDataSource {
    suspend fun permissions(sessionId: String, companyId: String): CompanyAccessDto

    suspend fun clientPolicy(sessionId: String, companyId: String): CompanyPolicyDto
}
