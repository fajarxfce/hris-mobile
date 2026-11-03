package dev.fajar.starter.collaboration.domain.entities

import kotlin.time.Instant

/** Private typed input; authenticated native metadata is not a company send grant. */
data class CollaborationNotificationReplyCandidate(
    val parent: CollaborationNativeParent,
    val action: CollaborationNotificationAction.Reply,
    val token: CollaborationNotificationActionToken,
    val text: String,
) {
    override fun toString() = "CollaborationNotificationReplyCandidate([redacted])"
}

data class CollaborationNotificationReplyRevision(val generation: String, val revision: Long)

enum class CollaborationNotificationReplyReview {
    SessionChanged,
    MessageChanged,
    ContextUnavailable,
    OriginalProcessing,
}

/** Explicitly confirmed send intent. Reapproval can replace only the native parent. */
data class CollaborationNotificationReplyResolution(
    val parent: CollaborationNativeParent,
    val operationId: String,
    val createdAt: Instant,
    val text: String,
) {
    override fun toString() = "CollaborationNotificationReplyResolution([redacted])"
}

data class CollaborationNotificationReplyInput(
    val id: String,
    val version: CollaborationNotificationReplyRevision,
    /** Local creation order only, never provider event order or an acknowledgment cursor. */
    val sequence: Long,
    val candidate: CollaborationNotificationReplyCandidate,
    val review: CollaborationNotificationReplyReview? = null,
    /** Editable local working copy, never a send grant; cleared atomically on confirmation. */
    val draft: String? = null,
    val resolution: CollaborationNotificationReplyResolution? = null,
) {
    override fun toString() = "CollaborationNotificationReplyInput([redacted])"
}

sealed interface CollaborationNotificationReplyRetention {
    data class Retained(val input: CollaborationNotificationReplyInput) :
        CollaborationNotificationReplyRetention {
        override fun toString() = "NotificationReplyRetained([redacted])"
    }

    data object CapacityExceeded : CollaborationNotificationReplyRetention

    data object Contended : CollaborationNotificationReplyRetention
}

data class CollaborationNotificationReplyPage(
    val inputs: List<CollaborationNotificationReplyInput>,
    val nextAfter: Long?,
) {
    override fun toString() = "CollaborationNotificationReplyPage([redacted])"
}
