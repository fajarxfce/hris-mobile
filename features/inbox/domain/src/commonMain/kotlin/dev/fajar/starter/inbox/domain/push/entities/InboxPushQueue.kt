package dev.fajar.starter.inbox.domain.push.entities

import kotlin.time.Instant

data class InboxPushOwner(val audience: String, val accountId: String) {
    override fun toString() = "InboxPushOwner([redacted])"
}

data class QueuedInboxPush(
    val eventId: String,
    val companyId: String,
    val inboxId: String,
    val receivedAt: Instant,
    val preparations: Int = 0,
) {
    override fun toString() = "QueuedInboxPush([redacted])"
}

/** A recorded display attempt prevents another alert after an uncertain OS callback. */
data class InboxPushAttempt(
    val eventId: String,
    val companyId: String,
    val owner: InboxPushOwner,
    val attemptedAt: Instant,
) {
    override fun toString() = "InboxPushAttempt([redacted])"
}

data class InboxPushQueue(
    val owner: InboxPushOwner? = null,
    val pending: List<QueuedInboxPush> = emptyList(),
    val attempts: List<InboxPushAttempt> = emptyList(),
) {
    override fun toString() = "InboxPushQueue([redacted])"
}
