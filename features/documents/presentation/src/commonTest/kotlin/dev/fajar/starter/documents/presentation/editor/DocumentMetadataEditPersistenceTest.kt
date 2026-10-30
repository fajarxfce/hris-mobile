package dev.fajar.starter.documents.presentation.editor

import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.domain.metadata.entities.DocumentMetadataHead
import dev.fajar.starter.documents.domain.metadata.working.entities.DocumentMetadataWorkingCopy
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class DocumentMetadataEditPersistenceTest {
    @Test
    fun editsDuringAnUnacknowledgedWriteAreCoalescedIntoTheNextStorageRevision() {
        val copy = copy()
        var local =
            DocumentMetadataEditPersistence(copy).edit(copy.input.copy(identityNumber = "First"))
        val first = local.mutation(copy.updatedAt + 1.seconds)
        local =
            local
                .copy(pending = first, writing = true)
                .edit(copy.input.copy(identityNumber = "Second"))
                .edit(copy.input.copy(identityNumber = "Final"))
        local = local.acknowledge(first.next, first)
        assertFalse(local.saved)
        assertEquals("Final", local.input.identityNumber)
        assertEquals("First", local.stored.input.identityNumber)
        val next = local.mutation(copy.updatedAt + 2.seconds)
        assertEquals(first.next, next.expected)
        assertEquals(copy.revision + 2, next.next.revision)
        assertEquals("Final", next.next.input.identityNumber)
        // A lost storage acknowledgement retains the original timestamp, input and revision.
        local =
            local.copy(
                pending = next,
                failure = Failure(FailureKind.Storage, "Lost acknowledgement"),
            )
        assertEquals(next, local.mutation(copy.updatedAt + 20.seconds))
        local = local.copy(failure = null).acknowledge(next.next, next)
        assertTrue(local.saved)
        assertEquals("Final", local.stored.input.identityNumber)
    }

    @Test
    fun aLateAcknowledgementCannotReplaceAnotherPendingMutationOrClearItsFailure() {
        val original = DocumentMetadataEditPersistence(copy())
        val firstEdit = original.edit(reason = "First reason")
        val first = firstEdit.mutation(original.stored.updatedAt + 1.seconds)
        val acknowledged = firstEdit.copy(pending = first).acknowledge(first.next, first)
        val nextEdit = acknowledged.edit(reason = "New reason")
        val second = nextEdit.mutation(original.stored.updatedAt + 2.seconds)
        val blocked =
            nextEdit.copy(
                pending = second,
                failure =
                    Failure(
                        FailureKind.Conflict,
                        "Changed elsewhere",
                        code = "client.document_working_copy_changed",
                    ),
            )
        assertEquals(blocked, blocked.acknowledge(first.next, first))
        val saved = blocked.acknowledge(second.next, second)
        assertEquals(blocked.failure, saved.failure)
        assertFalse(saved.saved)
        assertEquals("New reason", saved.reason)
    }

    private fun copy(): DocumentMetadataWorkingCopy {
        val now = Instant.parse("2026-10-30T00:00:00Z")
        val metadata =
            DocumentBusinessMetadata(DocumentCategory.PASSPORT, "Original", "AU", null, null)
        val head =
            DocumentMetadataHead(
                "11111111-1111-4111-8111-111111111111",
                "22222222-2222-4222-8222-222222222222",
                "Passport",
                DocumentClassification.PERSONAL,
                1,
                3,
                "33333333-3333-4333-8333-333333333333",
                now,
                null,
                metadata,
                2,
                true,
            )
        return DocumentMetadataWorkingCopy(
            "44444444-4444-4444-8444-444444444444",
            4,
            head,
            DocumentMetadataInput(DocumentCategory.PASSPORT, "Original", "AU"),
            "",
            now,
        )
    }
}
