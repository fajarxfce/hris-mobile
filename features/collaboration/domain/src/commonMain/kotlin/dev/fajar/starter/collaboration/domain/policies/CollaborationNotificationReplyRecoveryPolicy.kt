package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

const val COLLABORATION_NOTIFICATION_REPLY_RECOVERY_LIMIT = 4

fun validCollaborationNotificationReplyRecoverySequence(value: Long): Boolean =
    value in 0 until 9_007_199_254_740_991L

fun validCollaborationNotificationReplyRecoveryCursor(
    value: CollaborationNotificationReplyRecoveryCursor
): Boolean =
    validCollaborationMessageId(value.generation) &&
        validCollaborationNotificationReplyRecoverySequence(value.afterSequence) &&
        value.cycle?.let(::validCollaborationNotificationReplyRecoveryCycle) != false

fun validCollaborationNotificationReplyRecoveryCycle(
    value: CollaborationNotificationReplyRecoveryCycle
): Boolean =
    validCollaborationMessageId(value.id) &&
        (!value.initial || (!value.inFlight && !value.retryRequired && !value.finished)) &&
        (!value.finished || (!value.initial && !value.inFlight))

fun validCollaborationNotificationReplyRecoveryWork(
    value: CollaborationNotificationReplyRecoveryWork
): Boolean {
    val cursor = value.cursor
    val after = value.afterSequence
    val entries = value.entries
    if (cursor != null && !validCollaborationNotificationReplyRecoveryCursor(cursor)) return false
    if (after != null && after != cursor?.afterSequence) return false
    val cycle = cursor?.cycle
    if (
        cycle != null &&
            (cycle.finished || after != if (cycle.initial) null else cursor?.afterSequence)
    )
        return false
    if (
        entries.size !in 1..COLLABORATION_NOTIFICATION_REPLY_RECOVERY_LIMIT ||
            value.hasMore && entries.size != COLLABORATION_NOTIFICATION_REPLY_RECOVERY_LIMIT
    )
        return false
    if (
        entries.any {
            !validCollaborationNotificationReplyId(it.id) ||
                !validCollaborationNotificationReplyRecoverySequence(it.sequence) ||
                (after != null && it.sequence <= after)
        }
    )
        return false
    return entries.map { it.id }.distinct().size == entries.size &&
        entries.map { it.sequence } == entries.map { it.sequence }.distinct().sorted()
}
