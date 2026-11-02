@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.data.readstate.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.validCollaborationEventId
import dev.fajar.starter.collaboration.domain.repositories.CollaborationReadStateRepository
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.storage.safeStorageCall
import kotlin.uuid.Uuid
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.koin.core.annotation.*

@Single
class EncryptedCollaborationReadStateRepository(
    private val replicas: ReplicaStore,
    private val cipher: ContentCipher,
    @Named("collaboration-history-content-key") private val credentials: AtomicCredentialStore,
) : CollaborationReadStateRepository {
    private val keys = CollaborationHistoryKeys(replicas, cipher, credentials)

    override suspend fun read(
        access: CollaborationLocalAccess,
        conversationId: String,
        roomId: String,
        eventIds: List<String>,
    ) = safeStorageCall {
        validateReadStateRoom(roomId)
        val selected = select(eventIds)
        val scope = historyScope(access)
        val partition = readStatePartition(access, conversationId)
        val raw = replicas.checkpoint(scope, partition)
        if (raw == null)
            return@safeStorageCall CollaborationReadState(null, emptySet(), emptyList(), null)
        withCodec(access, conversationId, roomId) { codec ->
            val value = codec.checkpoint(raw)
            val marked = linkedSetOf<String>()
            for (eventId in selected) {
                val id = codec.id(eventId)
                replicas.document(scope, partition, id)?.let {
                    require(it.id == id && codec.event(raw, it) == eventId)
                    marked.add(eventId)
                }
            }
            require(
                marked.size.toLong() <= value.count && replicas.checkpoint(scope, partition) == raw
            )
            CollaborationReadState(
                raw.version(),
                marked.toSet(),
                value.pending.map { it.value() },
                value.acknowledged?.value(),
            )
        }
    }

    override suspend fun mark(
        access: CollaborationLocalAccess,
        conversationId: String,
        roomId: String,
        expected: CollaborationReadStateRevision?,
        eventIds: List<String>,
        intent: CollaborationReadReceiptIntent,
    ) = safeStorageCall {
        validateReadStateRoom(roomId)
        val selected = select(eventIds)
        require(selected.isNotEmpty() && validReadIntent(intent) && intent.eventId in selected)
        val scope = historyScope(access)
        val partition = readStatePartition(access, conversationId)
        val raw = replicas.checkpoint(scope, partition)
        if (raw?.version() != expected) return@safeStorageCall false
        withCodec(access, conversationId, roomId, create = raw == null) { codec ->
            val previous = raw?.let { codec.checkpoint(it) }
            val pending = appendIntent(previous, intent)
            val generation = raw?.handle?.generation ?: Uuid.random().toString()
            val revision = nextRevision(raw)
            val added = mutableListOf<ReplicaDocument>()
            for (eventId in selected) {
                val id = codec.id(eventId)
                val document = if (raw == null) null else replicas.document(scope, partition, id)
                if (document == null) added += codec.event(generation, revision, eventId)
                else
                    require(
                        document.id == id && codec.event(requireNotNull(raw), document) == eventId
                    )
            }
            val count = (previous?.count ?: 0) + added.size
            require(count in 1..9_007_199_254_740_991L)
            val payload =
                codec.checkpoint(
                    generation,
                    revision,
                    ReadStateCheckpointDto(count, pending, previous?.acknowledged),
                )
            currentCoroutineContext().ensureActive()
            if (raw == null)
                replicas.replace(
                    scope,
                    partition,
                    null,
                    ReplicaSnapshot(generation, payload, added),
                )
            else replicas.commit(raw.handle, raw.revision, payload, added, true)
        }
    }

    override suspend fun acknowledge(
        access: CollaborationLocalAccess,
        conversationId: String,
        roomId: String,
        intent: CollaborationReadReceiptIntent,
    ) = safeStorageCall {
        validateReadStateRoom(roomId)
        require(validReadIntent(intent))
        val scope = historyScope(access)
        val partition = readStatePartition(access, conversationId)
        val raw = replicas.checkpoint(scope, partition) ?: return@safeStorageCall false
        withCodec(access, conversationId, roomId) { codec ->
            val previous = codec.checkpoint(raw)
            if (previous.pending.none { it.value() == intent }) return@withCodec false
            val payload =
                codec.checkpoint(
                    raw.handle.generation,
                    nextRevision(raw),
                    previous.copy(
                        pending = previous.pending.filterNot { it.value() == intent },
                        acknowledged = intent.dto(),
                    ),
                )
            currentCoroutineContext().ensureActive()
            replicas.commit(raw.handle, raw.revision, payload, emptyList(), true)
        }
    }

    private fun appendIntent(
        previous: ReadStateCheckpointDto?,
        intent: CollaborationReadReceiptIntent,
    ): List<ReadIntentDto> {
        val pending = previous?.pending.orEmpty()
        val known =
            (pending + listOfNotNull(previous?.acknowledged)).firstOrNull { it.id == intent.id }
        if (known != null) {
            require(known.value() == intent)
            return pending
        }
        require(pending.size < 128)
        return pending + intent.dto()
    }

    private fun nextRevision(raw: ReplicaCheckpoint?): Long =
        (raw?.revision?.plus(1) ?: 0).also { require(it in 0 until 9_007_199_254_740_991L) }

    private fun select(eventIds: List<String>): List<String> {
        require(eventIds.size <= 128)
        return eventIds.toList().also {
            require(it.distinct().size == it.size && it.all(::validCollaborationEventId))
        }
    }

    private fun ReplicaCheckpoint.version() =
        CollaborationReadStateRevision(handle.generation, revision)

    private suspend fun <T> withCodec(
        access: CollaborationLocalAccess,
        conversationId: String,
        roomId: String,
        create: Boolean = false,
        block: suspend (CollaborationReadStateCodec) -> T,
    ): T =
        keys.use(create) { key, index ->
            block(CollaborationReadStateCodec(access, conversationId, roomId, cipher, key, index))
        }
}
