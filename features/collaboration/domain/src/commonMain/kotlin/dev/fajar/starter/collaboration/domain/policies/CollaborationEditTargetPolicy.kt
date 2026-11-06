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

/** Context of the original is immutable; a replacement has its own operation ID. */
fun validCollaborationEditIntent(intent: CollaborationMessageIntent): Boolean {
    val target = intent.editTarget ?: return true
    return validCollaborationEditTarget(target) &&
        intent.id != target.originalLogicalId &&
        intent.replyToEventId == target.replyToEventId &&
        intent.forwarded == target.forwarded
}

/** An edit cannot move to another room or native sender during staging/recovery. */
fun validCollaborationEditRecord(record: CollaborationMessageRecord): Boolean {
    if (!validCollaborationEditIntent(record.intent)) return false
    val target = record.intent.editTarget ?: return true
    val state = record.state
    val device =
        when (state) {
            CollaborationMessageState.Queued,
            CollaborationMessageState.Cancelled -> return true
            is CollaborationMessageState.Staging -> state.device
            is CollaborationMessageState.Staged -> state.device
            is CollaborationMessageState.Sent -> state.device
        }
    return device.roomId == target.roomId && state.origin?.sender == target.sender
}
