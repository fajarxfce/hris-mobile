package dev.fajar.starter.documents.domain.metadata

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.domain.metadata.entities.DocumentMetadataChange
import dev.fajar.starter.documents.domain.metadata.policies.validateDocumentMetadataChange
import kotlin.test.*
import kotlinx.datetime.LocalDate

class DocumentMetadataChangeTest {
    private val change =
        DocumentMetadataChange(
            "11111111-1111-1111-1111-111111111111",
            "22222222-2222-2222-2222-222222222222",
            4,
            DocumentBusinessMetadata(
                DocumentCategory.PASSPORT,
                "FIXTURE",
                "AU",
                LocalDate(2010, 1, 1),
                LocalDate(2020, 1, 1),
            ),
            "Correct printed validity",
        )

    @Test
    fun expiredEvidenceAndExactSignificantReasonRemainUnchanged() {
        val original = change.copy(reason = "  Correct printed validity  ")
        assertEquals(AppResult.Success(original), validateDocumentMetadataChange(original))
        assertFalse(original.toString().contains("FIXTURE"))
        assertFalse(original.toString().contains("printed"))
    }

    @Test
    fun invalidOperationVersionAndReasonCannotBecomeAConfirmedCommand() {
        for (invalid in
            listOf(
                change.copy(operationId = "invalid"),
                change.copy(documentId = "../elsewhere"),
                change.copy(expectedVersion = -1),
                change.copy(expectedVersion = 9_007_199_254_740_991L),
                change.copy(reason = " "),
                change.copy(reason = "x".repeat(1001)),
                change.copy(reason = "reason\u0085private"),
                change.copy(metadata = change.metadata.copy(issuingCountry = "ZZ")),
                change.copy(metadata = change.metadata.copy(identityNumber = " not normalized ")),
                change.copy(metadata = change.metadata.copy(validUntil = LocalDate(1900, 1, 1))),
            )) assertIs<AppResult.Failed>(validateDocumentMetadataChange(invalid))
        val largest =
            change.copy(expectedVersion = 9_007_199_254_740_990L, reason = "x".repeat(1000))
        assertEquals(AppResult.Success(largest), validateDocumentMetadataChange(largest))
    }
}
