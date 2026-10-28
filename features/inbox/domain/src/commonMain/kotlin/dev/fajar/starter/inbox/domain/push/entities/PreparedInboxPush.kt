package dev.fajar.starter.inbox.domain.push.entities

import dev.fajar.starter.inbox.domain.entities.InboxAccess
import kotlin.time.Instant

data class PreparedInboxPush(
    val workspaceId: String,
    val access: InboxAccess,
    val hint: QueuedInboxPush,
    val preparedAt: Instant,
) {
    override fun toString() = "PreparedInboxPush([redacted])"
}

/** Application-owned localized copy; provider text never enters this value. */
data class InboxPushCopy(val title: String, val body: String)
