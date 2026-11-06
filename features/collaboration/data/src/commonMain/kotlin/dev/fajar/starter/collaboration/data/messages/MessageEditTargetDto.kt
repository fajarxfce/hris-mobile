package dev.fajar.starter.collaboration.data.messages

import dev.fajar.starter.collaboration.domain.entities.CollaborationEditTarget
import dev.fajar.starter.collaboration.domain.policies.validCollaborationEditTarget
import kotlinx.serialization.Serializable

@Serializable
internal data class MessageEditTargetDto(
    val roomId: String,
    val eventId: String,
    val originalLogicalId: String?,
    val sender: String,
    val originalContentHash: String,
    val targetAcceptedAt: Long,
    val firstAcceptedAt: Long,
    val replyToEventId: String? = null,
    val forwarded: Boolean = false,
) {
    fun toDomain() =
        CollaborationEditTarget(
                roomId,
                eventId,
                originalLogicalId,
                sender,
                originalContentHash,
                targetAcceptedAt,
                firstAcceptedAt,
                replyToEventId,
                forwarded,
            )
            .also { require(validCollaborationEditTarget(it)) }

    override fun toString() = "MessageEditTargetDto([redacted])"
}

internal fun CollaborationEditTarget.toMessageEditDto() =
    MessageEditTargetDto(
            roomId,
            eventId,
            originalLogicalId,
            sender,
            originalContentHash,
            targetAcceptedAt,
            firstAcceptedAt,
            replyToEventId,
            forwarded,
        )
        .also { require(it.toDomain() == this) }
