package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*

class CollaborationSpaceRecipientRecoveryPolicyTest {
    private val f = SpaceRecipientRecoveryFixture()
    private val submission = f.source.submission
    private val frame =
        CollaborationSpaceRecipientTransferFrame(
            f.version,
            CollaborationSpaceConversationCatalogVersion(f.base.generation, 0),
            CollaborationSpaceInboxVersion(f.base.generation, 0, 0),
        )

    private fun previous(result: CollaborationSpaceRecipientTransfer) =
        CollaborationSpaceRecipientTransferAttempt(submission, frame, AppResult.Success(result))

    @Test
    fun conversationWaitIgnoresOtherSourceAndIndexChanges() {
        val wait = previous(CollaborationSpaceRecipientTransfer.WaitingForConversation(null))
        assertFalse(shouldAttemptCollaborationSpaceRecipientTransfer(submission, frame, wait))
        assertFalse(
            shouldAttemptCollaborationSpaceRecipientTransfer(
                submission,
                frame.copy(
                    source = f.version.copy(revision = 1),
                    destination = frame.destination!!.copy(revision = 1),
                ),
                wait,
            )
        )
        assertTrue(
            shouldAttemptCollaborationSpaceRecipientTransfer(
                submission,
                frame.copy(catalog = frame.catalog!!.copy(revision = 1)),
                wait,
            )
        )
    }

    @Test
    fun inboxWaitNeedsADestinationChangeRatherThanAnotherRemoval() {
        val wait = previous(CollaborationSpaceRecipientTransfer.WaitingForInbox(f.base.generation))
        assertFalse(
            shouldAttemptCollaborationSpaceRecipientTransfer(
                submission,
                frame.copy(source = f.version.copy(revision = 2)),
                wait,
            )
        )
        assertFalse(
            shouldAttemptCollaborationSpaceRecipientTransfer(
                submission,
                frame.copy(catalog = null),
                wait,
            )
        )
        assertTrue(
            shouldAttemptCollaborationSpaceRecipientTransfer(
                submission,
                frame.copy(destination = frame.destination!!.copy(revision = 1)),
                wait,
            )
        )
    }

    @Test
    fun changedOriginalAlwaysNeedsItsOwnAttempt() {
        val wait = previous(CollaborationSpaceRecipientTransfer.WaitingForConversation(null))
        assertTrue(
            shouldAttemptCollaborationSpaceRecipientTransfer(
                submission.copy(
                    original = submission.original.copy(text = "Different captured value")
                ),
                frame,
                wait,
            )
        )
        assertTrue(shouldAttemptCollaborationSpaceRecipientTransfer(submission, frame, null))
    }

    @Test
    fun completedProofIsOnlyRevisitedWhenTheSourceChanges() {
        val done = previous(CollaborationSpaceRecipientTransfer.Transferred(f.base.generation))
        assertFalse(
            shouldAttemptCollaborationSpaceRecipientTransfer(
                submission,
                frame.copy(destination = null, catalog = null),
                done,
            )
        )
        assertTrue(
            shouldAttemptCollaborationSpaceRecipientTransfer(
                submission,
                frame.copy(source = f.version.copy(revision = 1)),
                done,
            )
        )
    }

    @Test
    fun failedAttemptHasNoClockDrivenRetry() {
        val failed =
            CollaborationSpaceRecipientTransferAttempt(
                submission,
                frame,
                AppResult.Failed(f.base.storageFailure),
            )
        assertFalse(
            shouldAttemptCollaborationSpaceRecipientTransfer(submission, frame.copy(), failed)
        )
        assertTrue(
            shouldAttemptCollaborationSpaceRecipientTransfer(
                submission,
                frame.copy(source = f.version.copy(revision = 1)),
                failed,
            )
        )
        assertTrue(
            shouldAttemptCollaborationSpaceRecipientTransfer(
                submission,
                frame.copy(destination = null),
                failed,
            )
        )
    }

    @Test
    fun explicitlyEmptyAndLegacyVersionsRemainDistinctAndBounded() {
        assertTrue(validCollaborationSpaceRecipientOutboxVersion(f.version.copy(revision = null)))
        assertTrue(validCollaborationSpaceRecipientOutboxVersion(f.version.copy(revision = 0)))
        assertNotEquals(f.version.copy(revision = null), f.version)
        for (invalid in
            listOf(
                f.version.copy(generation = "wrong"),
                f.version.copy(revision = -1),
                f.version.copy(revision = 9_007_199_254_740_991L),
            )) assertFalse(validCollaborationSpaceRecipientOutboxVersion(invalid))
    }

    @Test
    fun invalidInputFrameDoesNotAuthorizeAScan() {
        assertTrue(validCollaborationSpaceRecipientTransferFrame(frame))
        assertTrue(
            validCollaborationSpaceRecipientTransferFrame(
                CollaborationSpaceRecipientTransferFrame(null, null, null)
            )
        )
        assertFalse(
            validCollaborationSpaceRecipientTransferFrame(
                frame.copy(source = f.version.copy(revision = -1))
            )
        )
        assertFalse(
            validCollaborationSpaceRecipientTransferFrame(
                frame.copy(catalog = frame.catalog!!.copy(revision = -1))
            )
        )
        assertFalse(
            validCollaborationSpaceRecipientTransferFrame(
                frame.copy(destination = frame.destination!!.copy(lastOrdinal = -1))
            )
        )
    }
}
