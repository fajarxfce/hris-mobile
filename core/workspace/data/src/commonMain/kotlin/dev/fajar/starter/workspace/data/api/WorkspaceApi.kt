package dev.fajar.starter.workspace.data.api

import dev.fajar.starter.network.*
import dev.fajar.starter.workspace.data.dto.CompanyAccessDto
import dev.fajar.starter.workspace.data.dto.CompanyPolicyDto
import io.ktor.client.HttpClient
import org.koin.core.annotation.*

@Single
class WorkspaceApi(@Named(HttpClients.Authenticated) private val client: HttpClient) {
    suspend fun permissions(sessionId: String, companyId: String): CompanyAccessDto =
        client.requestJson("companies/$companyId/me/access") { forSession(sessionId) }

    suspend fun clientPolicy(sessionId: String, companyId: String): CompanyPolicyDto =
        client.requestJson("companies/$companyId/client-policy") { forSession(sessionId) }
}
