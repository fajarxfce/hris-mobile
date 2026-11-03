package dev.fajar.hris.collaboration.proof

import dev.fajar.starter.collaboration.domain.entities.CollaborationNotificationReplyReview
import org.junit.Assert.*
import org.junit.Test

class NotificationInputCheckpointTest {
    @Test
    fun interruptedTransferMayRetainAnUnreviewedCopyBeforeOrAfterExactInputRemoval() {
        assertTrue(validNotificationInputCheckpoint(false, 1, emptyList()))
        assertTrue(validNotificationInputCheckpoint(false, 1, listOf(null)))
        assertTrue(validNotificationInputCheckpoint(false, 0, listOf(null)))
    }

    @Test
    fun completeRequiresFinalHeldCopyAndOriginalRetirement() {
        val held = listOf(CollaborationNotificationReplyReview.SessionChanged)
        assertTrue(validNotificationInputCheckpoint(true, 0, held))
        assertFalse(validNotificationInputCheckpoint(true, 1, held))
        assertFalse(validNotificationInputCheckpoint(true, 0, listOf(null)))
        assertFalse(validNotificationInputCheckpoint(true, 1, emptyList()))
    }

    @Test
    fun interruptionCannotExcuseLossDuplicationOrAnotherReviewReason() {
        for (complete in listOf(false, true)) {
            assertFalse(validNotificationInputCheckpoint(complete, 0, emptyList()))
            assertFalse(validNotificationInputCheckpoint(complete, 2, emptyList()))
            assertFalse(validNotificationInputCheckpoint(complete, 1, listOf(null, null)))
            assertFalse(
                validNotificationInputCheckpoint(
                    complete,
                    1,
                    listOf(CollaborationNotificationReplyReview.MessageChanged),
                )
            )
        }
    }
}
