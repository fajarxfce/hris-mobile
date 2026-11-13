package dev.fajar.starter.collaboration.data.history

import dev.fajar.starter.collaboration.data.inbox.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.database.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One conditional raw publication from an already authenticated source; never owns retries. */
internal class CollaborationHistoryInboxWriter(
    private val replicas: ReplicaStore,
    private val codec: CollaborationSpaceInboxCodec,
) {
    suspend fun publish(
        access: CollaborationSpaceLocalAccess,
        source: ReplicaCheckpoint,
        captured: CollaborationSpaceInboxPublication,
    ): Boolean {
        val scope = spaceHistoryScope(access)
        require(source.handle.scope == scope)
        val conversationId = captured.entry.conversationId
        val partition = spaceInboxPartition(access)
        val initial = replicas.checkpoint(scope, partition)
        if (initial?.published == false || (initial == null && captured.expected != null))
            return false
        if (initial?.let { codec.version(it) } != captured.expected) return false
        val ids =
            buildList {
                    add(codec.id("entry", conversationId))
                    add(codec.id("draft", conversationId))
                    captured.entry.newest?.let {
                        add(codec.id("activity", conversationId, it.source))
                    }
                    captured.activities.forEach {
                        add(codec.id("activity", conversationId, it.source))
                    }
                }
                .distinct()
        val current = replicas.workingDocuments(scope, partition, ids)
        if (current.checkpoint != initial) return false
        val mutation = codec.mutation(initial, current.documents, captured)
        currentCoroutineContext().ensureActive()
        if (mutation == null) {
            // Equality at both reads establishes a common unchanged source/index
            // observation. No empty commit or artificial source revision is needed.
            return replicas.checkpoint(scope, source.handle.partition) == source &&
                replicas.checkpoint(scope, partition) == initial
        }
        return replicas.mutate(
            scope,
            listOf(ReplicaMutation.Check(source.handle.partition, source), mutation),
        )
    }
}
