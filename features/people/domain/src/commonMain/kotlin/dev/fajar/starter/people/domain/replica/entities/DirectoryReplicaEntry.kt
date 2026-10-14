package dev.fajar.starter.people.domain.replica.entities

import dev.fajar.starter.sync.domain.entities.SyncReference

data class DirectoryReplicaEntry(val reference: SyncReference, val record: DirectoryRecord?) {
    override fun toString() = "DirectoryReplicaEntry([redacted])"
}
