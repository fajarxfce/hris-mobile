@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationHistoryTraversalRepository
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.storage.*
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.koin.core.annotation.*

@Single
class EncryptedCollaborationHistoryTraversalRepository(
    private val replicas: ReplicaStore,
    private val cipher: ContentCipher,
    @Named("collaboration-history-content-key") private val credentials: AtomicCredentialStore,
) : CollaborationHistoryTraversalRepository {
    private val keys = CollaborationHistoryKeys(replicas, cipher, credentials)

    override suspend fun progress(
        access: CollaborationLocalAccess,
        traversal: CollaborationHistoryTraversal,
    ) = safeStorageCall {
        val scope = historyScope(access)
        val partition = traversalPartition(access, traversal)
        val raw = replicas.checkpoint(scope, partition) ?: return@safeStorageCall null
        withCodec(access, traversal) { codec ->
            val progress = codec.progress(raw)
            require(replicas.checkpoint(scope, partition) == raw)
            progress
        }
    }

    override fun observeReferences(
        access: CollaborationLocalAccess,
        traversal: CollaborationHistoryTraversal,
        after: String?,
        limit: Int,
    ) =
        safeStorageFlow(
                flow {
                    require(
                        limit in 1..50 && (after == null || historyReferenceCursor.matches(after))
                    )
                    emitAll(
                        replicas.observeQueries(
                            historyScope(access),
                            traversalPartition(access, traversal),
                            listOf(ReplicaQuery(HISTORY_REFERENCES, after, limit + 1)),
                        )
                    )
                },
                mapValue = { page ->
                    require(page.pages.size == 1)
                    val documents = page.pages.single()
                    val raw = page.checkpoint
                    if (raw == null) {
                        require(documents.isEmpty())
                        CollaborationHistoryReferences(null, emptyList(), null)
                    } else
                        withCodec(access, traversal) { codec ->
                            val progress = codec.progress(raw)
                            require(
                                documents.size <= limit + 1 &&
                                    documents.size.toLong() <= progress.references
                            )
                            require(documents.zipWithNext().all { (a, b) -> a.sortKey < b.sortKey })
                            require(documents.all { after == null || it.sortKey > after })
                            val references = documents.map { codec.reference(raw, it) }
                            require(
                                references.all { !it.resolved && it.sequence < progress.references }
                            )
                            CollaborationHistoryReferences(
                                progress,
                                references.take(limit),
                                if (references.size > limit) documents[limit - 1].sortKey else null,
                            )
                        }
                },
            )
            .flowOn(Dispatchers.Default)

    override suspend fun stage(
        access: CollaborationLocalAccess,
        traversal: CollaborationHistoryTraversal,
        expected: CollaborationHistoryProgress?,
        page: CollaborationProviderHistoryPage,
        origin: CollaborationHistoryPageOrigin,
    ) = safeStorageCall {
        require(page.events.size <= 20)
        val captured = page.copy(events = page.events.toList())
        require(validCollaborationProviderPage(captured, access, traversal))
        val latest = origin == CollaborationHistoryPageOrigin.Latest
        require(latest || expected == null || !expected.exhausted)
        require(captured.requestedPosition == if (latest) null else expected?.next)
        val scope = historyScope(access)
        val partition = traversalPartition(access, traversal)
        val raw = replicas.checkpoint(scope, partition)
        withCodec(access, traversal, create = raw == null) { codec ->
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

    override suspend fun resolve(
        access: CollaborationLocalAccess,
        traversal: CollaborationHistoryTraversal,
        expected: CollaborationHistoryProgress,
        references: List<CollaborationHistoryReference>,
    ) = safeStorageCall {
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
        val scope = historyScope(access)
        val partition = traversalPartition(access, traversal)
        val raw = replicas.checkpoint(scope, partition) ?: return@safeStorageCall false
        withCodec(access, traversal) { codec ->
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
        access: CollaborationLocalAccess,
        traversal: CollaborationHistoryTraversal,
        create: Boolean = false,
        operation: suspend (CollaborationHistoryTraversalCodec) -> T,
    ): T =
        keys.use(create) { content, index ->
            operation(CollaborationHistoryTraversalCodec(access, traversal, cipher, content, index))
        }
}
