package dev.fajar.starter.documents.data

import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.data.api.DocumentsApi
import dev.fajar.starter.documents.data.datasources.ApiDocumentDataSource
import dev.fajar.starter.documents.data.dto.*
import dev.fajar.starter.documents.data.mappers.toCatalogDto
import dev.fajar.starter.documents.data.repositories.RemoteDocumentRepository
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.domain.replica.entities.DocumentCatalogRecord
import dev.fajar.starter.network.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

class DocumentMetadataTransportTest {
    @Test
    fun olderApiOmissionKeepsUnknownFactsAndCapabilityIndependent() = runTest {
        val f = DocumentDataFixture()
        val repository = RemoteDocumentRepository(f)
        val row = f.recordCall()
        val encoded = Json.encodeToJsonElement(row).jsonObject
        assertFalse(encoded.containsKey("metadata"))
        assertFalse(encoded.containsKey("metadataSupported"))
        val value =
            assertIs<AppResult.Success<DocumentCatalogRecord>>(repository.record(f.access, row.id))
                .value
        assertNull(value.metadata)
        assertNull(value.metadataVersion)
        assertFalse(value.metadataSupported)
        assertFalse(
            assertIs<AppResult.Success<DocumentEmploymentPage>>(
                    repository.employments(f.access, null)
                )
                .value
                .metadataSupported
        )
        f.directory = f.directory.copy(metadataSupported = true)
        assertTrue(
            assertIs<AppResult.Success<DocumentEmploymentPage>>(
                    repository.employments(f.access, null)
                )
                .value
                .metadataSupported
        )
    }

    @Test
    fun malformedOrUnsupportedMetadataCannotEnterTheOwnedReplica() = runTest {
        val f = DocumentDataFixture()
        val repository = RemoteDocumentRepository(f)
        val metadata =
            DocumentBusinessMetadataDto(
                "PASSPORT",
                "SAFE-FIXTURE",
                "AU",
                "2010-01-01",
                "2020-01-01",
            )
        val valid =
            f.recordCall().copy(metadata = metadata, metadataVersion = 0, metadataSupported = true)
        f.recordCall = { valid }
        val value =
            assertIs<AppResult.Success<DocumentCatalogRecord>>(
                    repository.record(f.access, valid.id)
                )
                .value
        assertEquals("2020-01-01", value.metadata?.validUntil.toString())
        assertFalse(value.toString().contains("SAFE-FIXTURE"))
        assertFalse(value.metadata.toString().contains("SAFE-FIXTURE"))
        assertFalse(metadata.toString().contains("SAFE-FIXTURE"))
        for (invalid in
            listOf(
                valid.copy(metadata = null),
                valid.copy(metadataVersion = null),
                valid.copy(metadataSupported = false),
                valid.copy(metadataVersion = -1),
                valid.copy(metadataVersion = 1000),
                valid.copy(metadataVersion = 1),
                valid.copy(metadata = metadata.copy(category = "UNKNOWN")),
                valid.copy(metadata = metadata.copy(identityNumber = "x".repeat(121))),
                valid.copy(metadata = metadata.copy(identityNumber = "fixture\nprivate")),
                valid.copy(metadata = metadata.copy(identityNumber = " trimmed ")),
                valid.copy(metadata = metadata.copy(issuingCountry = "aus")),
                valid.copy(metadata = metadata.copy(issuedOn = "2011-02-29")),
                valid.copy(metadata = metadata.copy(validUntil = "2009-12-31")),
                valid.copy(metadata = metadata.copy(validUntil = "2201-01-01")),
            )) {
            f.recordCall = { invalid }
            assertIs<AppResult.Failed>(repository.record(f.access, valid.id))
        }
    }

    @Test
    fun canonicalBusinessMetadataSurvivesTransportAndOwnedCacheEncoding() = runTest {
        val f = DocumentDataFixture()
        val row = f.recordCall()
        val metadata =
            Json.parseToJsonElement(
                """{"category":"PASSPORT","identityNumber":"PASSPORT-FIXTURE","issuingCountry":"AU","issuedOn":"2010-01-01","validUntil":"2020-01-01"}"""
            )
        val response =
            JsonObject(
                Json.encodeToJsonElement(row).jsonObject +
                    mapOf(
                        "metadata" to metadata,
                        "metadataVersion" to JsonPrimitive(0),
                        "metadataSupported" to JsonPrimitive(true),
                    )
            )
        val client =
            createHttpClient(
                MockEngine { request ->
                    assertEquals(f.access.sessionId, request.attributes[RequestSession])
                    assertEquals(HttpMethod.Get, request.method)
                    respond(
                        response.toString(),
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                },
                "https://hris.example.test/api/v1/",
            )
        try {
            val repository = RemoteDocumentRepository(ApiDocumentDataSource(DocumentsApi(client)))
            val record =
                assertIs<AppResult.Success<DocumentCatalogRecord>>(
                        repository.record(f.access, row.id)
                    )
                    .value
            val encoded = Json.encodeToJsonElement(record.toCatalogDto()).jsonObject
            assertEquals(metadata, encoded["metadata"])
            assertEquals(JsonPrimitive(0), encoded["metadataVersion"])
            assertEquals(JsonPrimitive(true), encoded["metadataSupported"])
            assertFalse(encoded.getValue("evidence").jsonObject.containsKey("metadata"))
        } finally {
            client.close()
        }
    }
}
