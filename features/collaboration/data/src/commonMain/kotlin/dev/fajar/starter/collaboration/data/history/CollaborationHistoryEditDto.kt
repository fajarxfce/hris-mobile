package dev.fajar.starter.collaboration.data.history

import dev.fajar.starter.collaboration.domain.entities.*
import kotlinx.serialization.Serializable

@Serializable
internal data class HistoryEditDto(
    val eventId: String,
    val sender: String,
    val targetEventId: String,
    val timestamp: Long,
    val text: String,
    val contentHash: String,
    val targetAcceptedAt: Long,
    val operationId: String? = null,
    val originalLogicalId: String? = null,
    val replyToEventId: String? = null,
    val forwarded: Boolean = false,
) {
    fun toDomain() =
        CollaborationMessageRevision(
            CollaborationMessageEdit(
                eventId,
                sender,
                targetEventId,
                timestamp,
                text,
                contentHash,
                operationId,
                originalLogicalId,
                replyToEventId,
                forwarded,
            ),
            targetAcceptedAt,
        )

    override fun toString() = "HistoryEditDto([redacted])"
}

internal fun CollaborationMessageRevision.toHistoryEditDto() =
    with(edit) {
        HistoryEditDto(
            eventId,
            sender,
            targetEventId,
            timestamp,
            text,
            contentHash,
            targetAcceptedAt,
            operationId,
            originalLogicalId,
            replyToEventId,
            forwarded,
        )
    }

@Serializable
internal data class HistoryRevisionDto(val messageId: String, val revision: HistoryEditDto) {
    override fun toString() = "HistoryRevisionDto([redacted])"
}
