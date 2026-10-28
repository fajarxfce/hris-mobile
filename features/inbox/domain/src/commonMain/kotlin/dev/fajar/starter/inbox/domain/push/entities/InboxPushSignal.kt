package dev.fajar.starter.inbox.domain.push.entities

/** Provider fields are routing hints, never trusted display text or authorization. */
data class InboxPushSignal(
    val eventId: String,
    val accountId: String,
    val companyId: String,
    val inboxId: String,
) {
    override fun toString() = "InboxPushSignal([redacted])"
}
