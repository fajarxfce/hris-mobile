package dev.fajar.starter.collaboration.data.history

import dev.fajar.starter.collaboration.data.inbox.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.database.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One conditional raw publication from authenticated source/index facts; never owns retries. */
internal class CollaborationHistoryInboxWriter(
    private val replicas: ReplicaStore,
    private val codec: CollaborationSpaceInboxCodec,
) {
    suspend fun publish(
        access: CollaborationSpaceLocalAccess,
        sourcePartition: String,
        source: ReplicaCheckpoint?,
        target: CollaborationHistoryInboxProposal,
    ): CollaborationHistoryInboxReceipt? {
        val scope = spaceHistoryScope(access)
        require(
            source == null ||
                source.handle.scope == scope && source.handle.partition == sourcePartition
        )
        val partition = spaceInboxPartition(access)
        require(sourcePartition != partition)
        val initial = replicas.checkpoint(scope, partition)
        if (initial?.published == false || (initial == null && target.expected != null)) return null
        val previous = initial?.let { codec.version(it) }
        if (previous != target.expected) return null
        val projection = target.projection
        val mutation =
            if (projection == null) null
            else {
                require(source != null)
                require(
                    projection.expected == target.expected &&
                        projection.entry.conversationId == target.conversationId
                )
                val ids =
                    buildList {
                            add(codec.id("entry", target.conversationId))
                            add(codec.id("draft", target.conversationId))
                            projection.entry.newest?.let {
                                add(codec.id("activity", target.conversationId, it.source))
                            }
                            projection.activities.forEach {
                                add(codec.id("activity", target.conversationId, it.source))
                            }
                        }
                        .distinct()
                val current = replicas.workingDocuments(scope, partition, ids)
                if (current.checkpoint != initial) return null
                codec.mutation(initial, current.documents, projection)
            }
        currentCoroutineContext().ensureActive()
        val sourceVersion =
            source?.let { CollaborationHistorySourceVersion(it.handle.generation, it.revision) }
        if (mutation == null) {
            // Bracketed equality establishes one unchanged source/index observation.
            if (
                replicas.checkpoint(scope, sourcePartition) != source ||
                    replicas.checkpoint(scope, partition) != initial
            )
                return null
            currentCoroutineContext().ensureActive()
            return CollaborationHistoryInboxReceipt(sourceVersion, previous)
        }
        val ordinal = requireNotNull(projection).lastOrdinal
        val destination =
            when (mutation) {
                is ReplicaMutation.Commit ->
                    CollaborationSpaceInboxVersion(
                        mutation.handle.generation,
                        mutation.expectedRevision + 1,
                        ordinal,
                    )
                is ReplicaMutation.Replace ->
                    CollaborationSpaceInboxVersion(
                        requireNotNull(mutation.snapshot).generation,
                        0,
                        ordinal,
                    )
                is ReplicaMutation.Check -> error("An inbox projection must contain a write")
            }
        val saved =
            replicas.mutate(scope, listOf(ReplicaMutation.Check(sourcePartition, source), mutation))
        currentCoroutineContext().ensureActive()
        return if (saved) CollaborationHistoryInboxReceipt(sourceVersion, destination) else null
    }
}
