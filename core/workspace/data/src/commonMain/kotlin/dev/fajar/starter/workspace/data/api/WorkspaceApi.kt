package dev.fajar.starter.workspace.data.api

import dev.fajar.starter.network.*
import dev.fajar.starter.workspace.data.dto.CompanyAccessDto
import io.ktor.client.HttpClient
import org.koin.core.annotation.*

@Single
class WorkspaceApi(@Named(HttpClients.Authenticated) private val client: HttpClient) {
    suspend fun permissions(sessionId: String, companyId: String): CompanyAccessDto =
        client.requestJson("companies/$companyId/me/access") { forSession(sessionId) }
}
