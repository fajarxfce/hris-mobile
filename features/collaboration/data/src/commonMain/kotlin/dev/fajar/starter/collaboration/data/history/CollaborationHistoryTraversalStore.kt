@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data.history

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.*
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Shared raw atomic traversal storage; typed repositories validate provenance and map storage
 * failures.
 */
internal class CollaborationHistoryTraversalStore(
    private val replicas: ReplicaStore,
    private val cipher: ContentCipher,
    credentials: AtomicCredentialStore,
) {
    private val keys = CollaborationHistoryKeys(replicas, cipher, credentials)

    suspend fun progress(
        coordinates: CollaborationHistoryCoordinates,
        traversal: CollaborationHistoryTraversal,
    ): CollaborationHistoryProgress? {
        val scope = coordinates.scope
        val partition = coordinates.traversalPartition(traversal)
        val raw = replicas.checkpoint(scope, partition) ?: return null
        return withCodec(coordinates, traversal) { codec ->
            val progress = codec.progress(raw)
            require(replicas.checkpoint(scope, partition) == raw)
            progress
        }
    }

    suspend fun source(
        coordinates: CollaborationHistoryCoordinates,
        traversal: CollaborationHistoryTraversal,
        eventId: String,
    ): CollaborationHistorySource {
        require(validCollaborationEventId(eventId))
        val scope = coordinates.scope
        val partition = coordinates.traversalPartition(traversal)
        // Avoid key acquisition for an absent partition. The final page still owns one transaction.
        if (replicas.checkpoint(scope, partition) == null)
            return CollaborationHistorySource(null, null)
        return withCodec(coordinates, traversal) { codec ->
            val id = codec.id(eventId)
            val page = replicas.observeDocument(scope, partition, id).first()
            val raw = page.checkpoint
            if (raw == null) {
                require(page.documents.isEmpty())
                CollaborationHistorySource(null, null)
            } else {
                require(page.documents.size <= 1)
                val progress = codec.progress(raw)
                val reference =
                    page.documents.singleOrNull()?.let { document ->
                        require(document.id == id)
                        codec.reference(raw, document).also {
                            require(
                                it.event.eventId == eventId && it.sequence < progress.references
                            )
                        }
                    }
                CollaborationHistorySource(progress, reference)
            }
        }
    }

    fun observeReferencePages(
        coordinates: CollaborationHistoryCoordinates,
        traversal: CollaborationHistoryTraversal,
        after: String?,
        limit: Int,
    ): Flow<ReplicaQueryPage> = flow {
        require(limit in 1..50 && (after == null || historyReferenceCursor.matches(after)))
        emitAll(
            replicas.observeQueries(
                coordinates.scope,
                coordinates.traversalPartition(traversal),
                listOf(ReplicaQuery(HISTORY_REFERENCES, after, limit + 1)),
            )
        )
    }

    suspend fun references(
        coordinates: CollaborationHistoryCoordinates,
        traversal: CollaborationHistoryTraversal,
        after: String?,
        limit: Int,
        page: ReplicaQueryPage,
    ): CollaborationHistoryReferences {
        require(page.pages.size == 1)
        val documents = page.pages.single()
        val raw = page.checkpoint
        return if (raw == null) {
            require(documents.isEmpty())
            CollaborationHistoryReferences(null, emptyList(), null)
        } else
            withCodec(coordinates, traversal) { codec ->
                val progress = codec.progress(raw)
                require(
                    documents.size <= limit + 1 && documents.size.toLong() <= progress.references
                )
                require(documents.zipWithNext().all { (a, b) -> a.sortKey < b.sortKey })
                require(documents.all { after == null || it.sortKey > after })
                val references = documents.map { codec.reference(raw, it) }
                require(references.all { !it.resolved && it.sequence < progress.references })
                CollaborationHistoryReferences(
                    progress,
                    references.take(limit),
                    if (references.size > limit) documents[limit - 1].sortKey else null,
                )
            }
    }

    suspend fun stage(
        coordinates: CollaborationHistoryCoordinates,
        traversal: CollaborationHistoryTraversal,
        expected: CollaborationHistoryProgress?,
        page: CollaborationProviderHistoryPage,
        origin: CollaborationHistoryPageOrigin,
        provider: CollaborationHistoryProviderIdentity?,
    ): Boolean {
        require(page.events.size <= 20)
        val captured = page.copy(events = page.events.toList())
        require(expected == null || expected.provider == provider)
        val latest = origin == CollaborationHistoryPageOrigin.Latest
        require(latest || expected == null || !expected.exhausted)
        require(captured.requestedPosition == if (latest) null else expected?.next)
        require(
            captured.requestedUntilPosition ==
                collaborationHistoryUntilPosition(traversal, expected, origin)
        )
        val scope = coordinates.scope
        val partition = coordinates.traversalPartition(traversal)
        val raw = replicas.checkpoint(scope, partition)
        return withCodec(coordinates, traversal, create = raw == null) { codec ->
            val previous = raw?.let { codec.progress(it) }
            if (previous != expected) return@withCodec false
            val added = mutableListOf<CollaborationHistoryReference>()
            var count = previous?.references ?: 0
            for (event in captured.events) {
                val existing = replicas.document(scope, partition, codec.id(event.eventId))
                if (existing != null) {
                    val known = codec.reference(requireNotNull(raw), existing)
                    require(
                        known.event == event && known.sequence < requireNotNull(previous).references
                    )
                } else {
                    require(count < 9_007_199_254_740_991L)
                    added += CollaborationHistoryReference(count++, event)
                }
            }
            val progress =
                CollaborationHistoryProgress(
                    raw?.handle?.generation ?: Uuid.random().toString(),
                    raw?.revision?.plus(1) ?: 0,
                    captured.next,
                    count,
                    collaborationIncomingHistoryPosition(traversal, previous, captured, origin),
                    provider,
                )
            val documents = added.map { codec.reference(progress, it) }
            val checkpoint = codec.checkpoint(progress)
            currentCoroutineContext().ensureActive()
            if (raw == null)
                replicas.replace(
                    scope,
                    partition,
                    null,
                    ReplicaSnapshot(progress.generation, checkpoint, documents),
                )
            else replicas.commit(raw.handle, raw.revision, checkpoint, documents, true)
        }
    }

    suspend fun resolve(
        coordinates: CollaborationHistoryCoordinates,
        traversal: CollaborationHistoryTraversal,
        expected: CollaborationHistoryProgress,
        references: List<CollaborationHistoryReference>,
    ): Boolean {
        require(references.size in 1..20)
        val captured = references.toList()
        require(validCollaborationHistoryProgress(expected))
        require(captured.map { it.event.eventId }.distinct().size == captured.size)
        require(
            captured.all {
                !it.resolved &&
                    it.sequence in 0 until expected.references &&
                    validCollaborationProviderEvent(it.event)
            }
        )
        val scope = coordinates.scope
        val partition = coordinates.traversalPartition(traversal)
        val raw = replicas.checkpoint(scope, partition) ?: return false
        return withCodec(coordinates, traversal) { codec ->
            if (codec.progress(raw) != expected) return@withCodec false
            for (reference in captured) {
                val row =
                    requireNotNull(
                        replicas.document(scope, partition, codec.id(reference.event.eventId))
                    )
                require(codec.reference(raw, row) == reference)
            }
            val progress = expected.copy(revision = expected.revision + 1)
            val documents = captured.map { codec.reference(progress, it.copy(resolved = true)) }
            val checkpoint = codec.checkpoint(progress)
            currentCoroutineContext().ensureActive()
            replicas.commit(raw.handle, raw.revision, checkpoint, documents, true)
        }
    }

    private suspend fun <T> withCodec(
        coordinates: CollaborationHistoryCoordinates,
        traversal: CollaborationHistoryTraversal,
        create: Boolean = false,
        operation: suspend (CollaborationHistoryTraversalCodec) -> T,
    ): T =
        keys.use(create) { content, index ->
            operation(
                CollaborationHistoryTraversalCodec(coordinates, traversal, cipher, content, index)
            )
        }

    suspend fun advanceIncomingReferences(
        coordinates: CollaborationHistoryCoordinates,
        traversal: CollaborationHistoryTraversal,
        source: CollaborationHistoryReferences,
        retryRequired: Boolean,
        limit: Int = 20,
    ): Boolean {
        require(
            traversal.purpose == CollaborationHistoryPurpose.Incoming && source.events.size <= 20
        )
        val captured = source.copy(events = source.events.toList())
        val expected = requireNotNull(captured.progress)
        val position = requireNotNull(expected.incoming)
        val through = position.pendingThrough ?: expected.references
        require(validCollaborationPendingHistoryReferences(captured, position.pendingAfter, limit))
        require(captured.events.all { it.sequence < through })
        val scope = coordinates.scope
        val partition = coordinates.traversalPartition(traversal)
        val raw = replicas.checkpoint(scope, partition) ?: return false
        return withCodec(coordinates, traversal) { codec ->
            val current = codec.progress(raw)
            if (
                current.generation != expected.generation ||
                    current.provider != expected.provider ||
                    current.revision < expected.revision ||
                    current.references < expected.references ||
                    current.incoming != position
            )
                return@withCodec false
            var last: ReplicaDocument? = null
            for (reference in captured.events) {
                val row =
                    requireNotNull(
                        replicas.document(scope, partition, codec.id(reference.event.eventId))
                    )
                require(codec.reference(raw, row).copy(resolved = false) == reference)
                last = row
            }
            val after = captured.after
            val previousAfter = position.pendingAfter
            require(
                after == null ||
                    (after == last?.sortKey &&
                        (previousAfter == null || after > previousAfter) &&
                        captured.events.last().sequence + 1 < through)
            )
            if (after == position.pendingAfter) return@withCodec true
            val progress =
                current.copy(
                    revision = current.revision + 1,
                    incoming =
                        position.copy(
                            pendingAfter = after,
                            pendingThrough = if (after == null) null else through,
                            pendingRetryRequired =
                                after != null && (position.pendingRetryRequired || retryRequired),
                        ),
                )
            val checkpoint = codec.checkpoint(progress)
            currentCoroutineContext().ensureActive()
            replicas.commit(raw.handle, raw.revision, checkpoint, emptyList(), true)
        }
    }
}
