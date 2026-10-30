package dev.fajar.starter.documents.domain.metadata

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.domain.metadata.entities.*
import dev.fajar.starter.documents.domain.metadata.policies.mergeDocumentMetadataPage
import kotlin.test.*
import kotlin.time.Instant

class DocumentMetadataHistoryTest {
    private val now = Instant.parse("2026-10-30T00:00:00Z")
    private val id = "11111111-1111-1111-1111-111111111111"
    private val generation = "22222222-2222-2222-2222-222222222222"
    private val metadata = DocumentBusinessMetadata(DocumentCategory.OTHER, null, null, null, null)
    private val head =
        DocumentMetadataHead(
            id,
            id,
            "Evidence",
            DocumentClassification.PERSONAL,
            1,
            21,
            id,
            now,
            null,
            metadata,
            20,
            true,
        )

    private fun row(version: Long) =
        DocumentMetadataRevision(
            id,
            version,
            version + 1,
            metadata,
            id,
            now,
            if (version == 0L) null else "Correction",
        )

    @Test
    fun partialHistoryAndCurrentFactsAdvanceIndependentlyWithoutAnExpiry() {
        val first = DocumentMetadataPage((0L..19L).map(::row), 19)
        val checkpoint =
            assertIs<AppResult.Success<DocumentMetadataCheckpoint>>(
                    mergeDocumentMetadataPage(null, head, first, null, now, generation)
                )
                .value
        assertEquals(20L, checkpoint.head.metadataVersion)
        assertEquals(19L, checkpoint.historyThrough)
        val complete =
            assertIs<AppResult.Success<DocumentMetadataCheckpoint>>(
                    mergeDocumentMetadataPage(
                        checkpoint,
                        head,
                        DocumentMetadataPage(listOf(row(20)), null),
                        19,
                        now,
                        generation,
                    )
                )
                .value
        assertEquals(20L, complete.historyThrough)
        assertEquals(1L, complete.revision)
        assertEquals(checkpoint.generation, complete.generation)
    }

    @Test
    fun concurrentHeadMismatchTruncationAndHistoryGapsCannotPublish() {
        val first = DocumentMetadataPage((0L..19L).map(::row), 19)
        val checkpoint =
            assertIs<AppResult.Success<DocumentMetadataCheckpoint>>(
                    mergeDocumentMetadataPage(null, head, first, null, now, generation)
                )
                .value
        val finalPage = DocumentMetadataPage(listOf(row(20)), null)
        for (changed in
            listOf(
                head.copy(id = generation),
                head.copy(employmentId = generation),
                head.copy(version = 20),
                head.copy(metadata = metadata.copy(identityNumber = "DIFFERENT")),
                head.copy(metadataVersion = 19),
            )) assertIs<AppResult.Failed>(
            mergeDocumentMetadataPage(checkpoint, changed, finalPage, 19, now, generation)
        )
        assertIs<AppResult.Failed>(
            mergeDocumentMetadataPage(null, head, finalPage, 19, now, generation)
        )
        assertIs<AppResult.Failed>(
            mergeDocumentMetadataPage(
                null,
                head,
                first.copy(nextCursor = null),
                null,
                now,
                generation,
            )
        )
        assertIs<AppResult.Failed>(
            mergeDocumentMetadataPage(
                checkpoint,
                head,
                DocumentMetadataPage(emptyList(), null),
                19,
                now,
                generation,
            )
        )
        assertIs<AppResult.Failed>(
            mergeDocumentMetadataPage(
                checkpoint,
                head,
                DocumentMetadataPage(
                    listOf(row(20).copy(metadata = metadata.copy(identityNumber = "MISMATCH"))),
                    null,
                ),
                19,
                now,
                generation,
            )
        )
    }

    @Test
    fun legacyUnknownCanUpgradeButCapabilityLossCannotEraseKnownFacts() {
        val legacy = head.copy(metadata = null, metadataVersion = null, metadataSupported = false)
        val empty = DocumentMetadataPage(emptyList(), null)
        val initial =
            assertIs<AppResult.Success<DocumentMetadataCheckpoint>>(
                    mergeDocumentMetadataPage(null, legacy, empty, null, now, generation)
                )
                .value
        assertNull(initial.historyThrough)
        val upgraded =
            assertIs<AppResult.Success<DocumentMetadataCheckpoint>>(
                    mergeDocumentMetadataPage(
                        initial,
                        head,
                        DocumentMetadataPage((0L..19L).map(::row), 19),
                        null,
                        now,
                        generation,
                    )
                )
                .value
        assertEquals(19L, upgraded.historyThrough)
        val failure =
            assertIs<AppResult.Failed>(
                mergeDocumentMetadataPage(upgraded, legacy, empty, null, now, generation)
            )
        assertEquals("client.document_metadata_unsupported", failure.failure.code)
    }
}
