package dev.fajar.starter.documents.presentation.changes

import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.domain.metadata.commands.entities.*
import dev.fajar.starter.documents.domain.metadata.entities.*
import dev.fajar.starter.documents.domain.metadata.working.entities.*
import kotlin.test.*
import kotlin.time.Instant

class DocumentMetadataChangesStateTest {
    @Test
    fun aFailedCommandReaderCannotExposeAPreviouslyReadDraft() {
        val (commands, copies) = views()
        val state =
            DocumentMetadataChangesState(
                commands = commands,
                copies = copies,
                loadingCommands = false,
                loadingCopies = false,
            )
        val failed =
            state.copy(
                commands = null,
                failure = Failure(FailureKind.Storage, "Command admission unavailable"),
            )
        assertTrue(failed.drafts.isEmpty())
        assertTrue(failed.records.isEmpty())
    }

    @Test
    fun aFailedWorkingReaderCannotExposeAPreviouslyReadOriginalCommand() {
        val (commands, copies) = views()
        val state =
            DocumentMetadataChangesState(
                commands = commands,
                copies = copies,
                loadingCommands = false,
                loadingCopies = false,
            )
        val failed =
            state.copy(
                copies = null,
                failure = Failure(FailureKind.Storage, "Working-copy admission unavailable"),
            )
        assertTrue(failed.drafts.isEmpty())
        assertTrue(failed.records.isEmpty())
    }

    @Test
    fun restartingReadersHidesOldRowsButKeepsKnownNeutralRecoveryTargets() {
        val (commands, copies) = views()
        val restriction =
            DocumentMetadataRestriction(
                "document",
                Failure(FailureKind.AccessDenied, "Denied", code = "document_access_denied"),
            )
        val state =
            DocumentMetadataChangesState(
                commands = commands,
                copies = copies.copy(restrictions = listOf(restriction)),
                loadingCommands = true,
                loadingCopies = true,
            )
        assertTrue(state.records.isEmpty())
        assertTrue(state.drafts.isEmpty())
        assertEquals(listOf(restriction), state.unavailable)
        val admitted = state.copy(copies = copies, loadingCommands = false, loadingCopies = false)
        assertEquals(commands.records, admitted.records)
        assertTrue(admitted.unavailable.isEmpty())
    }

    @Test
    fun aDenialFromEitherProjectionHidesBothRepresentationsDuringObserverDelivery() {
        val (commands, copies) = views()
        val restriction =
            DocumentMetadataRestriction(
                "document",
                Failure(FailureKind.AccessDenied, "Denied", code = "document_access_denied"),
            )
        for (state in
            listOf(
                DocumentMetadataChangesState(
                    commands =
                        commands.copy(records = emptyList(), restrictions = listOf(restriction)),
                    copies = copies,
                    loadingCommands = false,
                    loadingCopies = false,
                ),
                DocumentMetadataChangesState(
                    commands = commands,
                    copies =
                        copies.copy(
                            page = copies.page.copy(items = emptyList()),
                            restrictions = listOf(restriction),
                        ),
                    loadingCommands = false,
                    loadingCopies = false,
                ),
            )) {
            assertTrue(state.records.isEmpty())
            assertTrue(state.drafts.isEmpty())
            assertEquals(listOf(restriction), state.unavailable)
            assertEquals("document", state.copies?.page?.nextDocumentId)
        }
    }

    @Test
    fun anOriginalCommandTakesPrecedenceOverItsDraftWithoutChangingTheDraftCursor() {
        val (commands, copies) = views()
        val state =
            DocumentMetadataChangesState(
                commands = commands,
                copies = copies,
                loadingCommands = false,
                loadingCopies = false,
            )
        assertEquals(commands.records, state.records)
        assertTrue(state.drafts.isEmpty())
        assertEquals("document", state.copies?.page?.nextDocumentId)
        val reviewed = state.copy(commands = commands.copy(records = emptyList()))
        assertEquals(copies.page.items, reviewed.drafts)
    }

    private fun views(): Pair<DocumentMetadataCommandsView, DocumentMetadataWorkingView> {
        val now = Instant.parse("2026-10-30T00:00:00Z")
        val access =
            DocumentAccess(
                "session",
                "account",
                "https://hris.test/",
                "company",
                setOf("documents.self.read"),
                now,
                now,
                "token",
            )
        val metadata =
            DocumentBusinessMetadata(DocumentCategory.PASSPORT, "Original", "AU", null, null)
        val head =
            DocumentMetadataHead(
                "document",
                "employment",
                "Passport",
                DocumentClassification.PERSONAL,
                1,
                3,
                "creator",
                now,
                null,
                metadata,
                2,
                true,
            )
        val copy =
            DocumentMetadataWorkingCopy(
                "generation",
                0,
                head,
                DocumentMetadataInput(DocumentCategory.PASSPORT, "Original", "AU"),
                "Reason",
                now,
            )
        val change = DocumentMetadataChange("operation", "document", 3, metadata, "Reason")
        val command =
            DocumentMetadataCommand(
                "account",
                "https://hris.test/",
                "company",
                "employment",
                "Passport",
                change,
                now,
            )
        return DocumentMetadataCommandsView(
            access,
            listOf(
                DocumentMetadataCommandRecord(
                    command,
                    0,
                    0,
                    DocumentMetadataCommandOutcome.Unconfirmed(),
                )
            ),
            emptyList(),
        ) to
            DocumentMetadataWorkingView(
                access,
                DocumentMetadataWorkingPage(listOf(copy), "document"),
                emptyList(),
            )
    }
}
