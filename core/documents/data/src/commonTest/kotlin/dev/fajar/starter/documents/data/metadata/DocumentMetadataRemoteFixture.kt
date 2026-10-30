package dev.fajar.starter.documents.data.metadata

import dev.fajar.starter.documents.data.DocumentDataFixture
import dev.fajar.starter.documents.data.dto.DocumentBusinessMetadataDto
import dev.fajar.starter.documents.data.mappers.toBusinessMetadata
import dev.fajar.starter.documents.data.metadata.api.DocumentMetadataApi
import dev.fajar.starter.documents.data.metadata.datasources.ApiDocumentMetadataDataSource
import dev.fajar.starter.documents.data.metadata.dto.*
import dev.fajar.starter.documents.data.metadata.repositories.RemoteDocumentMetadataRepository
import dev.fajar.starter.documents.domain.metadata.entities.DocumentMetadataChange
import dev.fajar.starter.network.createHttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.request.HttpRequestData
import io.ktor.http.*

class DocumentMetadataRemoteFixture {
    private val existing = DocumentDataFixture()
    val access = existing.access
    val id = existing.item.documentId
    val metadata =
        DocumentBusinessMetadataDto(
            "PASSPORT",
            "FIXTURE-IDENTITY",
            "AU",
            "2010-01-01",
            "2020-01-01",
        )
    val head =
        DocumentMetadataHeadDto(
            id,
            existing.query.employmentId,
            "Passport",
            "PERSONAL",
            1,
            3,
            access.accountId,
            existing.now.toString(),
            null,
            metadata,
            2,
            true,
        )
    val change =
        DocumentMetadataChange(
            "66666666-6666-6666-6666-666666666666",
            id,
            3,
            metadata.toBusinessMetadata(),
            "FIXTURE-REASON",
        )

    fun revision(version: Long) =
        DocumentMetadataRevisionDto(
            id,
            version,
            version + 1,
            metadata,
            access.accountId,
            existing.now.toString(),
            if (version == 0L) null else "FIXTURE-REASON",
        )

    suspend fun withRemote(
        response: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        inspect: suspend (HttpRequestData) -> Unit = {},
        block: suspend (RemoteDocumentMetadataRepository) -> Unit,
    ) {
        val client =
            createHttpClient(
                MockEngine { request ->
                    inspect(request)
                    respond(
                        response,
                        status,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                },
                "https://hris.example.test/api/v1/",
            )
        try {
            block(
                RemoteDocumentMetadataRepository(
                    ApiDocumentMetadataDataSource(DocumentMetadataApi(client))
                )
            )
        } finally {
            client.close()
        }
    }
}
