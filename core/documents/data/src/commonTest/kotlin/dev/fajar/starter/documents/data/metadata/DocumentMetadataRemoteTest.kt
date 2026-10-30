package dev.fajar.starter.documents.data.metadata

import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.data.dto.DocumentMutationDto
import dev.fajar.starter.documents.data.metadata.dto.*
import dev.fajar.starter.documents.domain.metadata.entities.*
import dev.fajar.starter.network.RequestSession
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

class DocumentMetadataRemoteTest {
    @Test
    fun serverDenialsKeepTheirClassificationWithoutPrivateDiagnostics() = runTest {
        val f = DocumentMetadataRemoteFixture()
        f.withRemote(
            """{"code":"mfa_required","detail":"FIXTURE-PRIVATE-DIAGNOSTIC"}""",
            HttpStatusCode.Forbidden,
        ) { remote ->
            for (result in
                listOf(
                    remote.head(f.access, f.id),
                    remote.history(f.access, f.id, null),
                    remote.save(f.access, f.change),
                )) {
                val failure = assertIs<AppResult.Failed>(result).failure
                assertEquals(FailureKind.AccessDenied, failure.kind)
                assertEquals("mfa_required", failure.code)
                assertFalse(failure.toString().contains("FIXTURE"))
            }
        }
    }

    @Test
    fun ownedHeaderWithoutPublishedBinaryRetainsBusinessFactsAndLegacyUnknowns() = runTest {
        val f = DocumentMetadataRemoteFixture()
        f.withRemote(
            Json.encodeToString(f.head),
            inspect = { request ->
                assertEquals(HttpMethod.Get, request.method)
                assertEquals(
                    "/api/v1/companies/${f.access.companyId}/documents/${f.id}",
                    request.url.encodedPath,
                )
                assertEquals(f.access.sessionId, request.attributes[RequestSession])
            },
        ) { remote ->
            val head =
                assertIs<AppResult.Success<DocumentMetadataHead>>(remote.head(f.access, f.id)).value
            assertNull(head.currentRevisionId)
            assertEquals("FIXTURE-IDENTITY", head.metadata?.identityNumber)
            assertEquals("2020-01-01", head.metadata?.validUntil.toString())
            assertFalse(head.toString().contains("FIXTURE"))
            assertFalse(f.head.toString().contains("FIXTURE"))
        }
        val old = f.head.copy(metadata = null, metadataVersion = null, metadataSupported = false)
        val json = Json.encodeToJsonElement(old).jsonObject
        assertFalse(json.containsKey("metadata"))
        f.withRemote(json.toString()) { remote ->
            val head =
                assertIs<AppResult.Success<DocumentMetadataHead>>(remote.head(f.access, f.id)).value
            assertNull(head.metadata)
            assertNull(head.metadataVersion)
            assertFalse(head.metadataSupported)
        }
    }

    @Test
    fun wrongDocumentAndMalformedOrPrivateHeadersCannotEnterOwnedFacts() = runTest {
        val f = DocumentMetadataRemoteFixture()
        for (invalid in
            listOf(
                f.head.copy(id = f.access.companyId),
                f.head.copy(employmentId = "../other"),
                f.head.copy(classification = "HR_ONLY"),
                f.head.copy(title = ""),
                f.head.copy(version = -1),
                f.head.copy(version = 9_007_199_254_740_992L),
                f.head.copy(revisionCount = -1),
                f.head.copy(createdAt = "invalid"),
                f.head.copy(currentRevisionId = "invalid"),
                f.head.copy(metadata = null),
                f.head.copy(metadataVersion = null),
                f.head.copy(metadataSupported = false),
                f.head.copy(metadataVersion = 4),
                f.head.copy(version = 1000, metadataVersion = 1000),
                f.head.copy(metadata = f.metadata.copy(issuingCountry = "ZZ")),
            )) {
            f.withRemote(Json.encodeToString(invalid)) { remote ->
                assertIs<AppResult.Failed>(remote.head(f.access, f.id))
            }
        }
    }

    @Test
    fun boundedHistoryRetainsInitialUnknownActorAndUsesNumericContinuation() = runTest {
        val f = DocumentMetadataRemoteFixture()
        val initial = f.revision(0).copy(actorId = null, reason = null)
        val first = DocumentMetadataPageDto(listOf(initial) + (1L..19L).map(f::revision), "19")
        f.withRemote(
            Json.encodeToString(first),
            inspect = { request ->
                assertEquals(f.access.sessionId, request.attributes[RequestSession])
                assertEquals(
                    "/api/v1/companies/${f.access.companyId}/documents/${f.id}/metadata-history",
                    request.url.encodedPath,
                )
                assertEquals("20", request.url.parameters["limit"])
                assertNull(request.url.parameters["after"])
            },
        ) { remote ->
            val page =
                assertIs<AppResult.Success<DocumentMetadataPage>>(
                        remote.history(f.access, f.id, null)
                    )
                    .value
            assertEquals(20, page.items.size)
            assertEquals(19L, page.nextCursor)
            assertNull(page.items.first().actorId)
            assertNull(page.items.first().reason)
            assertFalse(page.toString().contains("FIXTURE"))
            assertFalse(first.toString().contains("FIXTURE"))
        }
        f.withRemote(
            Json.encodeToString(DocumentMetadataPageDto(listOf(f.revision(20)), null)),
            inspect = { assertEquals("19", it.url.parameters["after"]) },
        ) { remote ->
            val page =
                assertIs<AppResult.Success<DocumentMetadataPage>>(
                        remote.history(f.access, f.id, 19)
                    )
                    .value
            assertEquals(20L, page.items.single().version)
            assertNull(page.nextCursor)
        }
    }

    @Test
    fun missingDuplicatedCrossDocumentAndMalformedHistoryIsRejected() = runTest {
        val f = DocumentMetadataRemoteFixture()
        val first = f.revision(0)
        val second = f.revision(1)
        for (invalid in
            listOf(
                DocumentMetadataPageDto(listOf(second), null),
                DocumentMetadataPageDto(listOf(first, first), null),
                DocumentMetadataPageDto(
                    listOf(first, second.copy(documentId = f.access.companyId)),
                    null,
                ),
                DocumentMetadataPageDto(
                    listOf(first, second.copy(documentVersion = first.documentVersion)),
                    null,
                ),
                DocumentMetadataPageDto(listOf(first, second.copy(reason = null)), null),
                DocumentMetadataPageDto(listOf(first, second.copy(actorId = null)), null),
                DocumentMetadataPageDto(listOf(first, second.copy(reason = "bad\nreason")), null),
                DocumentMetadataPageDto(listOf(first), "0"),
                DocumentMetadataPageDto((0L..20L).map(f::revision), null),
                DocumentMetadataPageDto((0L..19L).map(f::revision), "019"),
                DocumentMetadataPageDto((0L..19L).map(f::revision), "18"),
                DocumentMetadataPageDto((0L..19L).map(f::revision), "bad"),
            )) {
            f.withRemote(Json.encodeToString(invalid)) { remote ->
                assertIs<AppResult.Failed>(remote.history(f.access, f.id, null))
            }
        }
    }

    @Test
    fun metadataRetryKeepsExactCommandAndReturnsOnlyOriginalReceiptVersion() = runTest {
        val f = DocumentMetadataRemoteFixture()
        val bodies = mutableListOf<String>()
        val clientReceipt = Json.encodeToString(DocumentMutationDto(f.id, 4))
        f.withRemote(
            clientReceipt,
            inspect = { request ->
                assertEquals(HttpMethod.Put, request.method)
                assertEquals(
                    "/api/v1/companies/${f.access.companyId}/documents/${f.id}/metadata",
                    request.url.encodedPath,
                )
                assertEquals(f.access.sessionId, request.attributes[RequestSession])
                assertEquals(f.change.operationId, request.headers["Idempotency-Key"])
                bodies += request.body.toByteArray().decodeToString()
            },
        ) { remote ->
            repeat(2) { assertIs<AppResult.Success<*>>(remote.save(f.access, f.change)) }
        }
        assertEquals(2, bodies.size)
        assertEquals(bodies[0], bodies[1])
        val body = Json.parseToJsonElement(bodies[0]).jsonObject
        assertEquals(setOf("expectedVersion", "metadata", "reason"), body.keys)
        assertEquals(JsonPrimitive(3), body["expectedVersion"])
        assertEquals(Json.encodeToJsonElement(f.metadata), body["metadata"])
        assertEquals(JsonPrimitive(f.change.reason), body["reason"])
        assertFalse(f.change.toString().contains("FIXTURE"))
    }

    @Test
    fun mismatchedReceiptCannotConfirmAndInvalidInputSendsNothing() = runTest {
        val f = DocumentMetadataRemoteFixture()
        for (receipt in
            listOf(DocumentMutationDto(f.id, 5), DocumentMutationDto(f.access.companyId, 4))) {
            f.withRemote(Json.encodeToString(receipt)) { remote ->
                assertIs<AppResult.Failed>(remote.save(f.access, f.change))
            }
        }
        f.withRemote("", inspect = { error("Invalid input must not use transport") }) { remote ->
            assertIs<AppResult.Failed>(remote.head(f.access, "../elsewhere"))
            assertIs<AppResult.Failed>(remote.history(f.access, f.id, -1))
            assertIs<AppResult.Failed>(remote.history(f.access, f.id, 1000))
            assertIs<AppResult.Failed>(remote.save(f.access, f.change.copy(reason = "")))
            assertIs<AppResult.Failed>(
                remote.save(f.access, f.change.copy(operationId = "invalid"))
            )
        }
    }

    @Test
    fun readResponsesAreBoundedAndCancellationIsNotMappedToFailure() = runTest {
        val f = DocumentMetadataRemoteFixture()
        f.withRemote(" ".repeat(16_385)) { remote ->
            assertIs<AppResult.Failed>(remote.head(f.access, f.id))
        }
        f.withRemote(" ".repeat(131_073)) { remote ->
            assertIs<AppResult.Failed>(remote.history(f.access, f.id, null))
        }
        f.withRemote("", inspect = { throw CancellationException("fixture cancellation") }) { remote
            ->
            assertFailsWith<CancellationException> { remote.head(f.access, f.id) }
            assertFailsWith<CancellationException> { remote.history(f.access, f.id, null) }
            assertFailsWith<CancellationException> { remote.save(f.access, f.change) }
        }
    }
}
