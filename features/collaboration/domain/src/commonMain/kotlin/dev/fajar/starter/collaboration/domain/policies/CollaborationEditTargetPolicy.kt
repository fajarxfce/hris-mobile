package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

fun validCollaborationEditTarget(target: CollaborationEditTarget): Boolean =
    target.roomId.startsWith('!') &&
        target.roomId.length in 2..255 &&
        target.roomId.none { it.isWhitespace() || it.isISOControl() } &&
        validCollaborationReplyEventId(target.eventId) &&
        (target.originalLogicalId == null ||
            validCollaborationMessageId(target.originalLogicalId)) &&
        validCollaborationMessageOrigin(
            CollaborationMessageOrigin(target.sender, target.originalContentHash)
        ) &&
        target.targetAcceptedAt in 0..9_007_199_254_740_991L &&
        target.firstAcceptedAt in 0..target.targetAcceptedAt &&
        (target.replyToEventId == null || validCollaborationReplyEventId(target.replyToEventId))

/** Device time is only a local authoring hint; receiver admission uses provider event time. */
fun canAuthorCollaborationEdit(
    target: CollaborationEditTarget,
    sender: String,
    now: Long,
): Boolean =
    validCollaborationEditTarget(target) &&
        target.sender == sender &&
        now >= target.targetAcceptedAt &&
        now - target.firstAcceptedAt < CollaborationMessageEditWindowMillis
