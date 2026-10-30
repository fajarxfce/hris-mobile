package dev.fajar.starter.documents.domain.metadata

import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.metadata.working.entities.DocumentMetadataWorkingCopy
import dev.fajar.starter.documents.domain.metadata.working.policies.*
import dev.fajar.starter.documents.domain.policies.prepareDocumentBusinessMetadata
import kotlin.test.*

class DocumentMetadataWorkingPolicyTest {
    @Test
    fun unfinishedInputRemainsADraftAndCannotBeMistakenForAValidCorrection() {
        val f = MetadataReadFixture()
        val copy =
            assertIs<AppResult.Success<DocumentMetadataWorkingCopy>>(
                    initialDocumentMetadataWorkingCopy(
                        f.head,
                        "77777777-7777-7777-7777-777777777777",
                        f.base.now,
                    )
                )
                .value
        val unfinished =
            copy.copy(
                input = copy.input.copy(issuedOn = "2026-", issuingCountry = "a"),
                reason = "Line one\nLine two",
            )
        assertIs<AppResult.Success<Unit>>(validateDocumentMetadataWorkingCopy(unfinished))
        assertIs<AppResult.Failed>(prepareDocumentBusinessMetadata(unfinished.input))
        assertFalse(unfinished.toString().contains("Line one"))
        assertIs<AppResult.Failed>(
            validateDocumentMetadataWorkingCopy(unfinished.copy(reason = "x".repeat(1001)))
        )
        assertIs<AppResult.Failed>(
            validateDocumentMetadataWorkingCopy(
                unfinished.copy(input = unfinished.input.copy(identityNumber = "x".repeat(241)))
            )
        )
    }

    @Test
    fun initialDraftRequiresAKnownCanonicalHeaderAndKeepsItsReviewedVersion() {
        val f = MetadataReadFixture()
        val copy =
            assertIs<AppResult.Success<DocumentMetadataWorkingCopy>>(
                    initialDocumentMetadataWorkingCopy(
                        f.head,
                        "77777777-7777-7777-7777-777777777777",
                        f.base.now,
                    )
                )
                .value
        assertEquals(f.head, copy.head)
        assertEquals(f.head.metadata?.identityNumber, copy.input.identityNumber)
        assertEquals(0L, copy.revision)
        assertIs<AppResult.Failed>(
            initialDocumentMetadataWorkingCopy(
                f.head.copy(metadataSupported = false),
                copy.generation,
                f.base.now,
            )
        )
        assertIs<AppResult.Failed>(
            initialDocumentMetadataWorkingCopy(
                f.head.copy(metadata = null),
                copy.generation,
                f.base.now,
            )
        )
        assertIs<AppResult.Failed>(
            validateDocumentMetadataWorkingCopy(copy.copy(generation = "invalid"))
        )
    }
}
