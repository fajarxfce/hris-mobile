package dev.fajar.starter.inbox.data.push.dto

import kotlinx.serialization.Serializable

@Serializable
data class InboxPushQueueDto(
    val format: Int = 1,
    val audience: String? = null,
    val accountId: String? = null,
    val pending: List<QueuedInboxPushDto> = emptyList(),
    val attempts: List<InboxPushAttemptDto> = emptyList(),
) {
    override fun toString() = "InboxPushQueueDto([redacted])"
}

@Serializable
data class QueuedInboxPushDto(
    val eventId: String,
    val companyId: String,
    val inboxId: String,
    val receivedAt: String,
    val preparations: Int = 0,
) {
    override fun toString() = "QueuedInboxPushDto([redacted])"
}

@Serializable
data class InboxPushAttemptDto(
    val eventId: String,
    val companyId: String,
    val audience: String,
    val accountId: String,
    val attemptedAt: String,
) {
    override fun toString() = "InboxPushAttemptDto([redacted])"
}
