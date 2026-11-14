package dev.fajar.starter.collaboration.presentation.recipient

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

/** Each local publication belongs to one read request and, where relevant, one real destination. */
internal sealed interface RecipientThreadReadResult {
    val request: Long

    data class Source(
        override val request: Long,
        val result: AppResult<CollaborationSpaceRecipientProjection>,
    ) : RecipientThreadReadResult {
        override fun toString() = "RecipientThreadSource([redacted])"
    }

    data class Destination(
        override val request: Long,
        val result: AppResult<CollaborationSpaceConversationMetadata?>,
    ) : RecipientThreadReadResult {
        override fun toString() = "RecipientThreadDestination([redacted])"
    }

    data class History(
        override val request: Long,
        val destination: CollaborationSpaceConversationMetadata,
        val result: AppResult<CollaborationSpaceThreadSnapshot>,
    ) : RecipientThreadReadResult {
        override fun toString() = "RecipientThreadHistory([redacted])"
    }

    data class Progress(
        override val request: Long,
        val destination: CollaborationSpaceConversationMetadata,
        val result: AppResult<List<CollaborationSpaceRecipientMessageProgress>>,
    ) : RecipientThreadReadResult {
        override fun toString() = "RecipientThreadProgress([redacted])"
    }
}
