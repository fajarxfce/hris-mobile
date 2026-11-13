package dev.fajar.starter.collaboration.data.history

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.CollaborationMessageEditWindowMillis
import kotlinx.serialization.Serializable

/** Body-free transport evidence survives display rejection and revision redaction. */
@Serializable
internal data class HistoryEditEvidenceDto(
    val messageId: String,
    val eventId: String,
    val sender: String,
    val targetEventId: String,
    val timestamp: Long,
    val contentHash: String,
    val operationId: String?,
    val inputFingerprint: String,
    val initiallyEligible: Boolean,
) {
    fun toDomain(
        original: CollaborationHistoryMessage,
        target: HistoryAliasDto,
        alias: HistoryAliasDto?,
    ): CollaborationEditEvidence {
        require(!target.replacement && target.messageId == messageId)
        require(target.eventId in original.eventIds && target.sender == original.sender)
        if (initiallyEligible) require(alias != null && sender == original.sender)
        alias?.let {
            require(it.sender == sender)
            if (it.replacement) {
                require(it.messageId == messageId && it.timestamp == timestamp)
                require(it.replacementHash == contentHash && it.replacementTarget == targetEventId)
            } else require(it.redacted && it.messageId == null && it.timestamp == null)
        }
        val redacted = original.redacted || alias?.redacted == true
        val first = requireNotNull(original.firstAcceptedAt)
        val eligible =
            initiallyEligible &&
                !redacted &&
                !target.redacted &&
                timestamp >= requireNotNull(target.timestamp) &&
                timestamp - first < CollaborationMessageEditWindowMillis
        return CollaborationEditEvidence(
            eventId,
            sender,
            targetEventId,
            timestamp,
            contentHash,
            operationId,
            eligible,
            redacted,
        )
    }

    override fun toString() = "HistoryEditEvidenceDto([redacted])"
}
