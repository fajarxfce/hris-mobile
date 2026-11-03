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
        validCollaborationNotificationTokenShape(value.token) &&
        validCollaborationText(value.text)
}

fun validCollaborationNotificationReplyInput(value: CollaborationNotificationReplyInput): Boolean =
    validCollaborationNotificationReplyId(value.id) &&
        validCollaborationMessageId(value.version.generation) &&
        value.version.revision in 0 until 9_007_199_254_740_991L &&
        value.sequence in 0..value.version.revision &&
        validCollaborationNotificationReplyCandidate(value.candidate) &&
        (value.draft == null ||
            (value.review != null &&
                value.resolution == null &&
                validCollaborationReplyDraft(value.draft))) &&
        (value.resolution == null ||
            (value.draft == null &&
                validCollaborationReplyResolution(value.candidate, value.resolution)))

fun validCollaborationReplyDraft(value: String): Boolean =
    value.length <= 16_384 &&
        value.none { it.isISOControl() && it !in "\n\r\t" } &&
        runCatching { value.encodeToByteArray(throwOnInvalidSequence = true).size <= 32_768 }
            .getOrDefault(false)

fun validCollaborationReplyResolution(
    candidate: CollaborationNotificationReplyCandidate,
    value: CollaborationNotificationReplyResolution,
): Boolean =
    value.parent.audience == candidate.parent.audience &&
        value.parent.accountId == candidate.parent.accountId &&
        value.parent.sessionId.length in 1..128 &&
        value.parent.sessionId.all { it.code in 33..126 } &&
        validCollaborationMessageId(value.parent.nativeSessionId) &&
        validCollaborationMessageId(value.operationId) &&
        value.operationId != candidate.action.operationId &&
        value.createdAt.toEpochMilliseconds() in 0..9_007_199_254_740_991L &&
        value.createdAt ==
            kotlin.time.Instant.fromEpochMilliseconds(value.createdAt.toEpochMilliseconds()) &&
        validCollaborationText(value.text)

fun validCollaborationNotificationReplyId(value: String): Boolean =
    notificationReplyId.matches(value)

private val notificationReplyId = Regex("n-[0-9a-f]{64}")
