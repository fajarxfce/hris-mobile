package dev.fajar.starter.collaboration.data.history

import dev.fajar.starter.collaboration.domain.entities.CollaborationHistoryMessage
import kotlinx.serialization.Serializable

@Serializable
internal data class HistoryCheckpointDto(
    val roomId: String,
    val count: Long,
    val schema: Int = 1,
    val hiddenRules: Long = 0,
)

@Serializable
internal data class HistoryMessageDto(
    val sender: String,
    val logicalId: String?,
    val primaryEventId: String,
    val timestamp: Long,
    val contentHash: String,
    val text: String?,
    val eventIds: List<String>,
    val redacted: Boolean,
    val locallyHidden: Boolean = false,
    val replyToEventId: String? = null,
    val forwarded: Boolean = false,
) {
    fun toDomain() =
        CollaborationHistoryMessage(
            sender,
            logicalId,
            primaryEventId,
            timestamp,
            contentHash,
            text,
            eventIds,
            redacted,
            locallyHidden,
            replyToEventId,
            forwarded,
        )

    override fun toString() = "HistoryMessageDto([redacted])"
}

internal fun CollaborationHistoryMessage.toHistoryDto() =
    HistoryMessageDto(
        sender,
        logicalId,
        primaryEventId,
        timestamp,
        contentHash,
        text,
        eventIds,
        redacted,
        locallyHidden,
        replyToEventId,
        forwarded,
    )

@Serializable
internal data class HistoryAliasDto(
    val eventId: String,
    val sender: String,
    val messageId: String?,
    val timestamp: Long?,
    val redacted: Boolean,
) {
    override fun toString() = "HistoryAliasDto([redacted])"
}
