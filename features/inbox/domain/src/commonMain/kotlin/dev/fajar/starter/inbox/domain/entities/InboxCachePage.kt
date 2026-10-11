package dev.fajar.starter.inbox.domain.entities

data class InboxCachePage(
    val checkpoint: InboxCheckpoint?,
    val items: List<InboxItem>,
    val nextCursor: String?,
) {
    override fun toString() = "InboxCachePage([redacted])"
}
