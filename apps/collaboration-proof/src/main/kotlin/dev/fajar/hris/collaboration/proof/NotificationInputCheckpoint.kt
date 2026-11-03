package dev.fajar.hris.collaboration.proof

import dev.fajar.starter.collaboration.domain.entities.CollaborationNotificationReplyReview

/** Transfer and review are separate durable commits within a finite recovery turn. */
internal fun validNotificationInputCheckpoint(
    complete: Boolean,
    protectedRows: Int,
    reviews: List<CollaborationNotificationReplyReview?>,
): Boolean {
    if (protectedRows !in 0..1 || reviews.size > 1 || protectedRows + reviews.size == 0)
        return false
    if (complete)
        return protectedRows == 0 &&
            reviews == listOf(CollaborationNotificationReplyReview.SessionChanged)
    return reviews.all { it == null || it == CollaborationNotificationReplyReview.SessionChanged }
}
