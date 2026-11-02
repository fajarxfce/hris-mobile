package dev.fajar.starter.collaboration.domain.entities

/** A valid envelope can request a full owned recovery sweep; it grants no content access. */
data class CollaborationPushSignal(
    val accountId: String,
    val nativeSessionId: String,
    val hint: CollaborationPushHint,
) {
    override fun toString() = "CollaborationPushSignal([redacted])"
}
