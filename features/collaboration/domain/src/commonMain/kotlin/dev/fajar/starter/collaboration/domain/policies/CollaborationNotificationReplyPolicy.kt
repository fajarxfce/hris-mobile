package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

const val COLLABORATION_NOTIFICATION_REPLY_CAPACITY = 64

fun validCollaborationNotificationReplyCandidate(
    value: CollaborationNotificationReplyCandidate
): Boolean {
    val parent = value.parent
    return parent.sessionId.length in 1..128 &&
        parent.sessionId.all { it.code in 33..126 } &&
        parent.audience.length in 1..2048 &&
        validCollaborationMessageId(parent.accountId) &&
        validCollaborationMessageId(parent.nativeSessionId) &&
        validCollaborationNotificationAction(value.action) &&
        value.token.encoded.length in 4..4096 &&
        value.token.encoded.startsWith("v1.") &&
        validCollaborationText(value.text)
}

fun validCollaborationNotificationReplyInput(value: CollaborationNotificationReplyInput): Boolean =
    validCollaborationNotificationReplyId(value.id) &&
        validCollaborationMessageId(value.version.generation) &&
        value.version.revision in 0 until 9_007_199_254_740_991L &&
        value.sequence in 0..value.version.revision &&
        validCollaborationNotificationReplyCandidate(value.candidate)

fun validCollaborationNotificationReplyId(value: String): Boolean =
    notificationReplyId.matches(value)

private val notificationReplyId = Regex("n-[0-9a-f]{64}")
