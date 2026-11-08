package dev.fajar.starter.collaboration.domain.entities

/** A local window and its owned commands; it grants no native or network authority. */
data class CollaborationThreadSnapshot(
    val context: CollaborationThreadContext,
    val history: CollaborationHistoryPage,
    val commands: List<CollaborationMessageRecord>,
    val recipientUnavailable: Boolean = false,
) {
    override fun toString() = "CollaborationThreadSnapshot([redacted])"
}

data class CollaborationHistoryWindow(
    val after: String? = null,
    val before: String? = null,
    val limit: Int = 50,
    val overlap: Int = 0,
) {
    override fun toString() = "CollaborationHistoryWindow([redacted])"
}
