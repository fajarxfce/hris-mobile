package dev.fajar.starter.collaboration.data.history

import dev.fajar.starter.collaboration.domain.policies.validCollaborationMessageId
import dev.fajar.starter.database.ReplicaCheckpoint
import dev.fajar.starter.database.ReplicaScope
import dev.fajar.starter.database.ReplicaSnapshot

/** Raw invalidation protocol. The constant payload contains no identity or message content. */
internal object CollaborationHistorySignal {
    const val PARTITION = "collaboration-history-signal/v1"
    private const val PAYLOAD = "history-changed-v1"

    fun generation(scope: ReplicaScope, raw: ReplicaCheckpoint): String {
        require(raw.handle.scope == scope && raw.handle.partition == PARTITION)
        require(raw.published && raw.revision == 0L && raw.payload == PAYLOAD)
        require(validCollaborationMessageId(raw.handle.generation))
        return raw.handle.generation
    }

    fun snapshot(generation: String): ReplicaSnapshot {
        require(validCollaborationMessageId(generation))
        return ReplicaSnapshot(generation, PAYLOAD, emptyList())
    }
}
