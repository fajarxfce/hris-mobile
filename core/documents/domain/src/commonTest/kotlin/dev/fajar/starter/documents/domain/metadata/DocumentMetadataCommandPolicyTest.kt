package dev.fajar.starter.documents.domain.metadata

import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.metadata.commands.entities.*
import dev.fajar.starter.documents.domain.metadata.commands.policies.*
import dev.fajar.starter.documents.domain.metadata.entities.DocumentMetadataChange
import kotlin.test.*

class DocumentMetadataCommandPolicyTest {
    @Test
    fun onlyAReviewedFirstDefiniteRejectionCanProveTheWriteDidNotCommit() {
        for (code in
            listOf(
                "invalid_document_metadata",
                "invalid_document_metadata_change",
                "document_metadata_limit",
                "document_metadata_unchanged",
                "document_archived",
                "document_not_found",
                "document_access_denied",
                "stale_version",
                "mfa_required",
                "company_access_denied",
                "session_revoked",
            )) {
            val failure = Failure(FailureKind.AccessDenied, "Denied", code = code)
            assertIs<DocumentMetadataCommandOutcome.Rejected>(
                resolveDocumentMetadataCommandFailure(
                    DocumentMetadataCommandOutcome.Pending,
                    failure,
                )
            )
            assertIs<DocumentMetadataCommandOutcome.Unconfirmed>(
                resolveDocumentMetadataCommandFailure(
                    DocumentMetadataCommandOutcome.Unconfirmed(),
                    failure,
                )
            )
        }
        for (code in
            listOf(
                null,
                "network",
                "client.invalid_response",
                "operation_payload_mismatch",
                "new_server_code",
            )) {
            assertIs<DocumentMetadataCommandOutcome.Unconfirmed>(
                resolveDocumentMetadataCommandFailure(
                    DocumentMetadataCommandOutcome.Pending,
                    Failure(FailureKind.Unexpected, "Unavailable", code = code),
                )
            )
        }
    }

    @Test
    fun frozenCommandKeepsExactReasonAndRequiresCanonicalOwnerAndVersion() {
        val f = MetadataReadFixture()
        val command =
            DocumentMetadataCommand(
                f.catalog.access.accountId,
                f.catalog.access.audience,
                f.catalog.access.companyId,
                f.head.employmentId,
                f.head.title,
                DocumentMetadataChange(
                    "66666666-6666-6666-6666-666666666666",
                    f.id,
                    f.head.version,
                    f.metadata,
                    " Original reason ",
                ),
                f.base.now,
            )
        assertIs<AppResult.Success<Unit>>(validateDocumentMetadataCommand(command))
        assertEquals(" Original reason ", command.change.reason)
        assertTrue(command.belongsTo(f.catalog.access.copy(sessionId = "replacement")))
        assertFalse(command.belongsTo(f.catalog.access.copy(companyId = f.id)))
        for (invalid in
            listOf(
                command.copy(employmentId = "other"),
                command.copy(documentTitle = " "),
                command.copy(change = command.change.copy(expectedVersion = -1)),
                command.copy(change = command.change.copy(reason = "bad\nreason")),
            )) {
            assertIs<AppResult.Failed>(validateDocumentMetadataCommand(invalid))
        }
        assertFalse(command.toString().contains("Original reason"))
        assertFalse(command.toString().contains("PRIVATE-FIXTURE"))
    }

    @Test
    fun terminalEvidenceCannotBeReplacedByAnUnrelatedLaterFailure() {
        val failure = Failure(FailureKind.Conflict, "Denied", code = "stale_version")
        for (terminal in
            listOf(
                DocumentMetadataCommandOutcome.Confirmed(4),
                DocumentMetadataCommandOutcome.Rejected(failure),
            )) {
            assertEquals(
                terminal,
                resolveDocumentMetadataCommandFailure(
                    terminal,
                    Failure(FailureKind.Network, "Offline"),
                ),
            )
        }
    }
}
