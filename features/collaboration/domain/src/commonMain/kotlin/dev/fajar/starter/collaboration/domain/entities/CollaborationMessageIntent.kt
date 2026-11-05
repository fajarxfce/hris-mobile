package dev.fajar.starter.collaboration.domain.entities

import kotlin.time.Instant

/** Stable account ownership, independent of an expiring login or SDK device family. */
data class CollaborationMessageOwner(
    val audience: String,
    val accountId: String,
    val companyId: String,
) {
    override fun toString() = "CollaborationMessageOwner([redacted])"
}

/** One explicit send action. Retries retain this ID and exact text, including whitespace. */
data class CollaborationMessageIntent(
    val id: String,
    val owner: CollaborationMessageOwner,
    val conversationId: String,
    val createdAt: Instant,
    val text: String,
    val replyToEventId: String? = null,
    val forwarded: Boolean = false,
) {
    override fun toString() = "CollaborationMessageIntent([redacted])"
}

/** Binds uncertain native work to the original protected SDK store, not just its account. */
data class CollaborationMessageDevice(
    val nativeSessionId: String,
    val deviceId: String,
    val bootstrapOperationId: String,
    val roomId: String,
) {
    override fun toString() = "CollaborationMessageDevice([redacted])"
}

sealed interface CollaborationMessageState {
    /** Null only for previously stored commands that lack a pre-enqueue content identity. */
    val origin: CollaborationMessageOrigin?
        get() = null

    data object Queued : CollaborationMessageState

    /** Authenticated archive of the original queued record; never an active SDK command. */
    data object Cancelled : CollaborationMessageState

    /** Written before enqueue while SDK delivery and the exclusive writer are paused. */
    data class Staging(
        val device: CollaborationMessageDevice,
        val before: Set<String>,
        override val origin: CollaborationMessageOrigin? = null,
    ) : CollaborationMessageState {
        override fun toString() = "Staging([redacted])"
    }

    /** The mapping must be durable before the SDK can deliver this transaction. */
    data class Staged(
        val device: CollaborationMessageDevice,
        val transactionId: String,
        override val origin: CollaborationMessageOrigin? = null,
    ) : CollaborationMessageState {
        override fun toString() = "Staged([redacted])"
    }

    data class Sent(
        val device: CollaborationMessageDevice,
        val transactionId: String,
        val eventId: String,
        override val origin: CollaborationMessageOrigin? = null,
    ) : CollaborationMessageState {
        override fun toString() = "Sent([redacted])"
    }
}

data class CollaborationMessageRecord(
    val intent: CollaborationMessageIntent,
    val revision: Long,
    val state: CollaborationMessageState,
) {
    override fun toString() = "CollaborationMessageRecord([redacted])"
}

fun CollaborationLocalAccess.messageOwner() =
    CollaborationMessageOwner(audience, accountId, companyId)

enum class CollaborationMessageInsertion {
    STORED,
    CAPACITY_EXCEEDED,
    SCOPE_CHANGED,
}

/** Only a complete, paused queue snapshot under the same exclusive writer can justify Missing. */
sealed interface CollaborationQueueRecovery {
    data object Missing : CollaborationQueueRecovery

    data class Found(val transactionId: String) : CollaborationQueueRecovery {
        override fun toString() = "Found([redacted])"
    }

    data object Ambiguous : CollaborationQueueRecovery

    data object DifferentDevice : CollaborationQueueRecovery
}
