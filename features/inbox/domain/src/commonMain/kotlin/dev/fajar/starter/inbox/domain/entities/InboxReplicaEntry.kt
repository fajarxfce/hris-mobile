package dev.fajar.starter.inbox.domain.entities

/** A null item is a versioned tombstone, not a failed download. */
data class InboxReplicaEntry(val id: String, val version: Long, val item: InboxItem?) {
    override fun toString() = "InboxReplicaEntry([redacted])"
}
