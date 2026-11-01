@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationHistoryRepository
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.storage.*
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.koin.core.annotation.*

@Single
class EncryptedCollaborationHistoryRepository(
    private val replicas: ReplicaStore,
    private val cipher: ContentCipher,
    @Named("collaboration-history-content-key") private val credentials: AtomicCredentialStore,
) : CollaborationHistoryRepository {
    private val keys = CollaborationHistoryKeys(replicas, cipher, credentials)

    override fun observe(
        access: CollaborationLocalAccess,
        conversationId: String,
        roomId: String?,
        after: String?,
        limit: Int,
    ) =
        safeStorageFlow(
                flow {
                    require(limit in 1..50 && (after == null || historyCursor.matches(after)))
                    emitAll(
                        replicas.observeQueries(
                            historyScope(access),
                            historyPartition(access, conversationId),
                            listOf(ReplicaQuery(HISTORY_MESSAGES, after, limit + 1)),
                        )
                    )
                },
                mapValue = { page ->
                    require(page.pages.size == 1)
                    val documents = page.pages.single()
                    val raw = page.checkpoint
                    if (raw == null) {
                        require(documents.isEmpty())
                        CollaborationHistoryPage(emptyList(), null)
                    } else
                        withKey(access, conversationId, roomId) { codec ->
                            val checkpoint = codec.checkpoint(raw)
                            require(
                                documents.size <= limit + 1 &&
                                    documents.size.toLong() <= checkpoint.count
                            )
                            require(documents.zipWithNext().all { (a, b) -> a.sortKey < b.sortKey })
                            require(documents.all { after == null || it.sortKey > after })
                            val messages = documents.map { codec.message(raw, it) }
                            CollaborationHistoryPage(
                                messages.take(limit),
                                if (messages.size > limit) documents[limit - 1].sortKey else null,
                            )
                        }
                },
            )
            .flowOn(Dispatchers.Default)

    override suspend fun getByEvent(
        access: CollaborationLocalAccess,
        conversationId: String,
        roomId: String,
        eventId: String,
    ) = safeStorageCall {
        val scope = historyScope(access)
        val partition = historyPartition(access, conversationId)
        // A missing owned partition does not require (or create) a content key.
        val raw = replicas.checkpoint(scope, partition) ?: return@safeStorageCall null
        withKey(access, conversationId, roomId) { codec ->
            codec.checkpoint(raw)
            val aliasId = codec.aliasId(eventId)
            val alias = replicas.document(scope, partition, aliasId)?.let { codec.alias(raw, it) }
            val message =
                alias?.messageId?.let { id ->
                    val document = requireNotNull(replicas.document(scope, partition, id))
                    require(document.id == id)
                    codec.message(raw, document).also {
                        require(it.sender == alias.sender && eventId in it.eventIds)
                        require(!alias.redacted || it.redacted)
                    }
                }
            // Do not combine different generations/revisions or publish through a late logout.
            require(replicas.checkpoint(scope, partition) == raw)
            message
        }
    }

    override suspend fun merge(
        access: CollaborationLocalAccess,
        conversationId: String,
        roomId: String,
        changes: List<CollaborationHistoryChange>,
    ) = safeStorageCall {
        require(changes.size in 1..16)
        val batch = changes.toList()
        val scope = historyScope(access)
        val partition = historyPartition(access, conversationId)
        val raw = replicas.checkpoint(scope, partition)
        withKey(access, conversationId, roomId, create = raw == null) { codec ->
            val previous = raw?.let { codec.checkpoint(it) }
            val mutation = CollaborationHistoryBatch(replicas, scope, partition, raw, codec)
            mutation.apply(batch)
            val generation = raw?.handle?.generation ?: Uuid.random().toString()
            val revision = raw?.revision?.plus(1) ?: 0
            val count = (previous?.count ?: 0) + mutation.added
            require(count in 0..9_007_199_254_740_991L)
            val documents = mutation.documents(generation, revision)
            val checkpoint = codec.checkpoint(generation, revision, count)
            currentCoroutineContext().ensureActive()
            if (raw == null)
                replicas.replace(
                    scope,
                    partition,
                    null,
                    ReplicaSnapshot(generation, checkpoint, documents),
                )
            else replicas.commit(raw.handle, raw.revision, checkpoint, documents, true)
        }
    }

    override suspend fun getByLogicalId(
        access: CollaborationLocalAccess,
        conversationId: String,
        roomId: String,
        sender: String,
        logicalId: String,
    ) = safeStorageCall {
        val scope = historyScope(access)
        val partition = historyPartition(access, conversationId)
        val raw = replicas.checkpoint(scope, partition) ?: return@safeStorageCall null
        withKey(access, conversationId, roomId) { codec ->
            codec.checkpoint(raw)
            val id = codec.messageId(sender, logicalId, null)
            val message =
                replicas.document(scope, partition, id)?.let {
                    require(it.id == id)
                    codec.message(raw, it)
                }
            require(replicas.checkpoint(scope, partition) == raw)
            message
        }
    }

    private suspend fun <T> withKey(
        access: CollaborationLocalAccess,
        conversationId: String,
        roomId: String?,
        create: Boolean = false,
        operation: suspend (CollaborationHistoryCodec) -> T,
    ): T {
        return keys.use(create) { content, index ->
            operation(
                CollaborationHistoryCodec(access, conversationId, roomId, cipher, content, index)
            )
        }
    }
}
