package dev.fajar.starter.inbox.domain.entities

/** Delivery evidence, independent of whether a screen or process is still alive. */
data class InboxCommandRecord(
    val command: InboxCommand,
    val revision: Long = 0,
    val attempts: Int = 0,
    val outcome: InboxCommandOutcome = InboxCommandOutcome.Pending,
) {
    override fun toString() = "InboxCommandRecord(revision=$revision, attempts=$attempts)"
}
