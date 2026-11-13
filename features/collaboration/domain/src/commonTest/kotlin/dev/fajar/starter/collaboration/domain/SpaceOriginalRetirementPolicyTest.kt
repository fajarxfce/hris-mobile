package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import kotlin.test.*

class SpaceOriginalRetirementPolicyTest {
    private val f = SpaceReceiptProjectionFixture()
    private val original = history(f.original.eventIds)

    @Test
    fun exactSentJournalAndRetainedHistoryProduceAnImmutableCompletion() {
        assertEquals(
            f.completion,
            collaborationSpaceOriginalRetirementCompletion(f.access, f.sent, f.original, original),
        )
    }

    @Test
    fun anotherSenderLogicalIdBodyOrContentHashCannotRetireTheCommand() {
        for (changed in
            listOf(
                original.copy(sender = "@another:matrix.example.test"),
                original.copy(logicalId = SpaceReceiptProjectionFixture.id(900)),
                original.copy(text = "Different content"),
                original.copy(contentHash = "b".repeat(64)),
            )) assertNull(
            collaborationSpaceOriginalRetirementCompletion(f.access, f.sent, f.original, changed)
        )
    }

    @Test
    fun aLaterAliasIsProvedByHistoryWithoutExtendingTheArchivedCompletion() {
        val later = f.original.copy(eventIds = listOf("\$later"))
        val retained = history(f.original.eventIds + later.eventIds)
        assertEquals(
            f.completion,
            collaborationSpaceOriginalRetirementCompletion(
                f.access,
                f.sent,
                later,
                retained,
                f.completion,
            ),
        )
        assertNull(
            collaborationSpaceOriginalRetirementCompletion(f.access, f.sent, later, retained)
        )
    }

    @Test
    fun everyOldAndNewAliasMustBePresentBeforeReceiptRemoval() {
        val later = f.original.copy(eventIds = listOf("\$later"))
        for (retained in listOf(original, history(later.eventIds))) {
            assertNull(
                collaborationSpaceOriginalRetirementCompletion(
                    f.access,
                    f.sent,
                    later,
                    retained,
                    f.completion,
                )
            )
        }
    }

    @Test
    fun localHidingAndValidRedactionPreserveTheOriginalRetirementEvidence() {
        for (retained in
            listOf(
                original.copy(locallyHidden = true),
                original.copy(redacted = true, text = null),
            )) {
            assertTrue(validCollaborationHistoryMessage(retained))
            assertEquals(
                f.completion,
                collaborationSpaceOriginalRetirementCompletion(
                    f.access,
                    f.sent,
                    f.original,
                    retained,
                ),
            )
        }
        assertNull(
            collaborationSpaceOriginalRetirementCompletion(
                f.access,
                f.sent,
                f.original,
                original.copy(redacted = true),
            )
        )
    }

    @Test
    fun stagedOrQueuedRecordsAndContradictoryArchivesCannotBeRetired() {
        assertNull(
            collaborationSpaceOriginalRetirementCompletion(f.access, f.staged, f.original, original)
        )
        assertNull(
            collaborationSpaceOriginalRetirementCompletion(
                f.access,
                f.sent.copy(revision = 0, state = CollaborationMessageState.Queued),
                f.original,
                original,
            )
        )
        val other =
            f.completion.copy(
                record = f.sent.copy(intent = f.sent.intent.copy(text = "Other original"))
            )
        assertNull(
            collaborationSpaceOriginalRetirementCompletion(
                f.access,
                f.sent,
                f.original,
                original,
                other,
            )
        )
    }

    @Test
    fun accountGroupAndConversationCannotAdoptAnotherOriginalsEvidence() {
        for (access in
            listOf(
                f.access.copy(accountId = SpaceReceiptProjectionFixture.id(901)),
                f.access.copy(spaceId = SpaceReceiptProjectionFixture.id(902)),
            )) {
            assertNull(
                collaborationSpaceOriginalRetirementCompletion(access, f.sent, f.original, original)
            )
        }
        val receipt =
            f.original.copy(
                intent =
                    f.original.intent.copy(conversationId = SpaceReceiptProjectionFixture.id(903))
            )
        assertNull(
            collaborationSpaceOriginalRetirementCompletion(f.access, f.sent, receipt, original)
        )
    }

    @Test
    fun returnedCompletionDoesNotRetainTheCallersMutableEventList() {
        val events = f.original.eventIds.toMutableList()
        val receipt = f.original.copy(eventIds = events)
        val result =
            assertNotNull(
                collaborationSpaceOriginalRetirementCompletion(f.access, f.sent, receipt, original)
            )
        events.clear()
        assertEquals(f.completion, result)
    }

    private fun history(events: List<String>): CollaborationHistoryMessage {
        var value: CollaborationHistoryMessage? = null
        for (event in events) value =
            assertNotNull(
                mergeCollaborationHistoryText(
                    value,
                    CollaborationHistoryChange.Text(
                        event,
                        f.original.origin.sender,
                        f.sent.intent.id,
                        100,
                        f.sent.intent.text,
                        f.original.origin.contentHash,
                    ),
                )
            )
        return assertNotNull(value)
    }
}
