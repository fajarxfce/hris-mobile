package dev.fajar.starter.collaboration.domain.entities

import kotlin.time.Instant

/** An owned composer slot. Empty replacements retain their revision to fence late writers. */
data class CollaborationDraft(
    val conversationId: String,
    val revision: String,
    val text: String,
    val savedAt: Instant,
    /** Non-null freezes this exact body and ID until its local command is reconciled. */
    val submissionId: String? = null,
) {
    override fun toString() = "CollaborationDraft([redacted])"
}
