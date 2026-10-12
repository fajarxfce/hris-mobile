package dev.fajar.starter.documents.data.api

import dev.fajar.starter.documents.data.dto.SelfDocumentEvidenceDto
import dev.fajar.starter.network.*
import io.ktor.client.HttpClient
import io.ktor.client.request.parameter
import org.koin.core.annotation.*

@Single
class DocumentsApi(@Named(HttpClients.Authenticated) private val client: HttpClient) {
    suspend fun evidence(
        sessionId: String,
        companyId: String,
        employmentId: String,
        classification: String,
        after: String?,
    ): SelfDocumentEvidenceDto =
        client.requestJson("companies/$companyId/documents/me/evidence", maximumBytes = 65_536) {
            forSession(sessionId)
            parameter("employmentId", employmentId)
            parameter("classification", classification)
            parameter("limit", 20)
            after?.let { parameter("after", it) }
        }
}
