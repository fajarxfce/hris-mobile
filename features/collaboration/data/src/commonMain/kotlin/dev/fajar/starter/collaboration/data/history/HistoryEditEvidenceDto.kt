package dev.fajar.starter.collaboration.data.history

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
    override fun toString() = "HistoryEditEvidenceDto([redacted])"
}
