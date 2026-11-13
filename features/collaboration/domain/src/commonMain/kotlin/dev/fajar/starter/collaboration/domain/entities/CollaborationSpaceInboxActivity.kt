package dev.fajar.starter.collaboration.domain.entities

/** Original storage identity, not an alias inferred from a logical message ID. */
sealed interface CollaborationSpaceInboxSource {
    data class GroupOriginal(val id: String) : CollaborationSpaceInboxSource

    data class CompanyOriginal(val companyId: String, val conversationId: String, val id: String) :
        CollaborationSpaceInboxSource

    data class History(
        val companyId: String?,
        val conversationId: String,
        val roomId: String,
        val sender: String,
        val eventId: String,
    ) : CollaborationSpaceInboxSource
}

/** Assigned once. The ordinal is allocated by the persisted inbox transaction, never an UUID. */
data class CollaborationSpaceInboxPosition(val timestamp: Long, val ordinal: Long)

enum class CollaborationSpaceInboxContent {
    TEXT,
    IMAGE,
    VIDEO,
    AUDIO,
    FILE,
    STICKER,
    UNAVAILABLE,
    DELETED,
}

data class CollaborationSpaceInboxPreview(
    val kind: CollaborationSpaceInboxContent,
    val text: String?,
    /** Actual message time; acknowledgement never replaces the retained placement. */
    val timestamp: Long,
    /** Null for incoming history. SENT is server acknowledgement, not a peer/read receipt. */
    val delivery: CollaborationMessageDelivery?,
) {
    override fun toString() = "CollaborationSpaceInboxPreview([redacted])"
}

/** A hidden candidate retains its original anchor so later reconciliation cannot move it. */
data class CollaborationSpaceInboxActivity(
    val source: CollaborationSpaceInboxSource,
    val position: CollaborationSpaceInboxPosition,
    val preview: CollaborationSpaceInboxPreview?,
    val pendingEdit: CollaborationSpaceInboxPendingEdit? = null,
) {
    override fun toString() = "CollaborationSpaceInboxActivity([redacted])"
}

/** Empty and frozen slots are retained for source reconciliation, but are not draft previews. */
data class CollaborationSpaceInboxDraft(
    val value: CollaborationDraft,
    val position: CollaborationSpaceInboxPosition,
) {
    override fun toString() = "CollaborationSpaceInboxDraft([redacted])"
}

/** Globally ranked immutable row; label/participant admission stays with canonical metadata. */
data class CollaborationSpaceInboxEntry(
    val conversationId: String,
    val createdAt: Long,
    val draft: CollaborationSpaceInboxDraft?,
    val newest: CollaborationSpaceInboxActivity?,
    val position: CollaborationSpaceInboxPosition,
) {
    override fun toString() = "CollaborationSpaceInboxEntry([redacted])"
}
