package dev.fajar.starter.people.domain.profile.entities

import dev.fajar.starter.sync.domain.entities.SyncReference

data class SelfProfileReplicaEntry(val reference: SyncReference, val record: SelfProfileRecord?) {
    override fun toString() = "SelfProfileReplicaEntry([redacted])"
}
