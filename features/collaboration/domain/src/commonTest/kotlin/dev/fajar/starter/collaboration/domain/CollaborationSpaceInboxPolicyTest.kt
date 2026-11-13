package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import kotlin.test.*
import kotlin.time.Instant

class CollaborationSpaceInboxPolicyTest {
    private val conversation = "00000000-0000-4000-8000-000000000001"
    private val original = "00000000-0000-4000-8000-000000000002"
    private val candidate =
        CollaborationSpaceInboxActivity(
            CollaborationSpaceInboxSource.GroupOriginal(original),
            CollaborationSpaceInboxPosition(1000, 1),
            CollaborationSpaceInboxPreview(
                CollaborationSpaceInboxContent.TEXT,
                "Hello",
                1000,
                CollaborationMessageDelivery.PENDING,
            ),
        )

    @Test
    fun incomingActivityKeepsTheEditablePreviewWhileAdvancingRecency() {
        val draft =
            CollaborationSpaceInboxDraft(
                CollaborationDraft(
                    conversation,
                    original,
                    "Still editing",
                    Instant.fromEpochMilliseconds(900),
                ),
                CollaborationSpaceInboxPosition(900, 2),
            )
        val entry = collaborationSpaceInboxEntry(conversation, 0, draft, candidate)
        assertEquals(draft, entry.draft)
        assertEquals(candidate.position, entry.position)
    }

    @Test
    fun frozenOrClearedDraftCannotHideActualMessageActivity() {
        val draft =
            CollaborationSpaceInboxDraft(
                CollaborationDraft(
                    conversation,
                    original,
                    "Frozen body",
                    Instant.fromEpochMilliseconds(9000),
                    original,
                ),
                CollaborationSpaceInboxPosition(9000, 2),
            )
        assertNull(collaborationSpaceInboxEntry(conversation, 0, draft, candidate).draft)
        val cleared = draft.copy(value = draft.value.copy(text = "", submissionId = null))
        assertEquals(
            candidate.position,
            collaborationSpaceInboxEntry(conversation, 0, cleared, candidate).position,
        )
    }

    @Test
    fun equalTimesUseRetainedInsertionOrderAndAcknowledgedDisplayTimeDoesNotMoveIt() {
        val newer = candidate.copy(position = candidate.position.copy(ordinal = 2))
        assertTrue(compareCollaborationSpaceInboxPositions(newer.position, candidate.position) > 0)
        val acknowledged =
            newer.copy(
                preview =
                    newer.preview!!.copy(
                        timestamp = 50,
                        delivery = CollaborationMessageDelivery.SENT,
                    )
            )
        assertEquals(
            newer.position,
            collaborationSpaceInboxEntry(conversation, 0, null, acknowledged).position,
        )
        assertEquals(50L, acknowledged.preview?.timestamp)
    }

    @Test
    fun historyCannotClaimPendingOrFailedAndSourceNamespacesStayDistinct() {
        val source =
            CollaborationSpaceInboxSource.History(
                null,
                conversation,
                "!room:matrix.test",
                "@peer:matrix.test",
                "\$event",
            )
        assertFalse(validCollaborationSpaceInboxActivity(candidate.copy(source = source)))
        assertTrue(
            validCollaborationSpaceInboxActivity(
                candidate.copy(source = source, preview = candidate.preview!!.copy(delivery = null))
            )
        )
        assertFalse(validCollaborationSpaceInboxSource(source.copy(sender = "@bad sender")))
        assertFalse(validCollaborationSpaceInboxSource(source.copy(roomId = "!bad room")))
        assertNotEquals<CollaborationSpaceInboxSource>(
            candidate.source,
            CollaborationSpaceInboxSource.CompanyOriginal(original, conversation, original),
        )
    }

    @Test
    fun invalidProjectionOrDuplicateCandidateCannotEnterAReadyPublication() {
        val entry = collaborationSpaceInboxEntry(conversation, 0, null, candidate)
        val proposal =
            CollaborationSpaceInboxPublication(null, 1, entry, activities = listOf(candidate))
        assertTrue(validCollaborationSpaceInboxPublication(proposal))
        assertFalse(
            validCollaborationSpaceInboxPublication(
                proposal.copy(activities = listOf(candidate, candidate))
            )
        )
        assertFalse(
            validCollaborationSpaceInboxPublication(
                proposal.copy(
                    entry = entry.copy(position = CollaborationSpaceInboxPosition(10_000, 1))
                )
            )
        )
        assertFalse(validCollaborationSpaceInboxPublication(proposal.copy(lastOrdinal = 0)))
        assertFalse(
            validCollaborationSpaceInboxPublication(
                proposal.copy(entry = entry.copy(newest = candidate.copy(preview = null)))
            )
        )
    }

    @Test
    fun nonTextContentKeepsItsKindAndEmptyMetadataHasNoInventedMessageReceipt() {
        val file =
            candidate.copy(
                preview =
                    candidate.preview!!.copy(
                        kind = CollaborationSpaceInboxContent.FILE,
                        text = null,
                    )
            )
        assertTrue(validCollaborationSpaceInboxActivity(file))
        assertFalse(
            validCollaborationSpaceInboxActivity(
                file.copy(preview = file.preview!!.copy(text = "technical payload"))
            )
        )
        val entry = collaborationSpaceInboxEntry(conversation, 1234, null, null)
        assertEquals(CollaborationSpaceInboxPosition(1234, 0), entry.position)
        assertNull(entry.newest)
    }
}
