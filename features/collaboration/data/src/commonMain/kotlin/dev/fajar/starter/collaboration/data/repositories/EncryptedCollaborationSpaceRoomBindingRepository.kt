@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.history.CollaborationHistoryKeys
import dev.fajar.starter.collaboration.data.identity.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceRoomBindingInventoryRepository
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceRoomBindingRepository
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.storage.safeStorageCall
import kotlin.uuid.Uuid
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import org.koin.core.annotation.*

@Single
class EncryptedCollaborationSpaceRoomBindingRepository(
    private val replicas: ReplicaStore,
    private val cipher: ContentCipher,
    @Named("collaboration-history-content-key") private val credentials: AtomicCredentialStore,
) : CollaborationSpaceRoomBindingRepository, CollaborationSpaceRoomBindingInventoryRepository {
    private val keys = CollaborationHistoryKeys(replicas, cipher, credentials)

    override suspend fun getByConversation(
        access: CollaborationSpaceLocalAccess,
        conversationId: String,
    ) = safeStorageCall {
        require(validCollaborationSpaceConversationId(conversationId))
        lookup(access) { it.conversationId(conversationId) }
    }

    override suspend fun findByRoom(
        access: CollaborationSpaceLocalAccess,
        serverName: String,
        roomId: String,
    ) = safeStorageCall {
        require(validCollaborationSpaceRoomIdentity(serverName, roomId))
        lookup(access) { it.roomId(serverName, roomId) }
    }

    override suspend fun retain(
        access: CollaborationSpaceLocalAccess,
        binding: CollaborationSpaceRoomBinding,
    ) = safeStorageCall {
        val scope = spaceRoomBindingScope(access)
        val partition = spaceRoomBindingPartition(access)
        require(validCollaborationSpaceRoomBinding(access, binding))
        val existing = replicas.checkpoint(scope, partition)
        keys.use(create = existing == null) { content, index ->
            val codec = CollaborationSpaceRoomBindingCodec(access, cipher, content, index)
            val ids = codec.ids(binding)
            val page = replicas.workingDocuments(scope, partition, ids)
            val checkpoint = page.checkpoint
            if (checkpoint == null) {
                require(page.documents.isEmpty())
                val generation = Uuid.random().toString()
                val snapshot =
                    ReplicaSnapshot(
                        generation,
                        codec.checkpoint(generation, 0),
                        codec.documents(generation, 0, binding),
                    )
                currentCoroutineContext().ensureActive()
                return@use replicas.replace(scope, partition, null, snapshot)
            }
            codec.validate(checkpoint)
            require(page.documents.all { it.id in ids })
            if (page.documents.isNotEmpty()) {
                val same = codec.pair(checkpoint, page.documents) == binding
                // Recheck ownership/revision after suspended decryption without writing or
                // retrying.
                return@use same && replicas.checkpoint(scope, partition) == checkpoint
            }
            require(checkpoint.revision < 9_007_199_254_740_991L)
            val revision = checkpoint.revision + 1
            val payload = codec.checkpoint(checkpoint.handle.generation, revision)
            val documents = codec.documents(checkpoint.handle.generation, revision, binding)
            currentCoroutineContext().ensureActive()
            replicas.commit(
                checkpoint.handle,
                checkpoint.revision,
                payload,
                documents,
                publish = true,
            )
        }
    }

    override suspend fun page(
        access: CollaborationSpaceLocalAccess,
        after: CollaborationSpaceRoomBindingCursor?,
        limit: Int,
    ) = safeStorageCall {
        val scope = spaceRoomBindingScope(access)
        val partition = spaceRoomBindingPartition(access)
        require(limit in 1..50)
        after?.let {
            val boundary = it.boundary
            require(
                boundary.audience == access.audience &&
                    boundary.accountId == access.accountId &&
                    boundary.spaceId == access.spaceId &&
                    validCollaborationSpaceConversationId(boundary.generation) &&
                    boundary.revision in 0..9_007_199_254_740_991L &&
                    it.afterId.matches(Regex("b-[0-9a-f]{64}"))
            )
        }
        val raw = replicas.observe(scope, partition, after?.afterId, limit + 1).first()
        val checkpoint = raw.checkpoint
        if (checkpoint == null) {
            check(after == null && raw.documents.isEmpty())
            return@safeStorageCall CollaborationSpaceRoomBindingPage(null, emptyList(), null, false)
        }
        keys.use { content, index ->
            val codec = CollaborationSpaceRoomBindingCodec(access, cipher, content, index)
            codec.validate(checkpoint)
            val boundary =
                after?.boundary
                    ?: CollaborationSpaceRoomBindingBoundary(
                        access.audience,
                        access.accountId,
                        access.spaceId,
                        checkpoint.handle.generation,
                        checkpoint.revision,
                    )
            check(
                checkpoint.handle.generation == boundary.generation &&
                    checkpoint.revision >= boundary.revision
            )
            val ids = raw.documents.map { it.id }
            require(
                raw.documents.size <= limit + 1 &&
                    ids == ids.sorted() &&
                    ids.distinct() == ids &&
                    ids.all { after == null || it > after.afterId }
            )
            // Authenticate lookahead too; a malformed row must not invent another page.
            val decoded = raw.documents.map { codec.decode(checkpoint, it) }
            val bindings = mutableListOf<CollaborationSpaceRoomBinding>()
            var observedRevision = checkpoint.revision
            for ((position, document) in raw.documents.take(limit).withIndex()) {
                val value = decoded[position]
                val pairIds = codec.ids(value)
                val pair =
                    replicas
                        .observeQueries(
                            scope,
                            partition,
                            pairIds.map { ReplicaQuery(tag = it, limit = 2) },
                        )
                        .first()
                val current = requireNotNull(pair.checkpoint)
                codec.validate(current)
                check(
                    current.handle.generation == boundary.generation &&
                        current.revision >= observedRevision
                )
                require(pair.pages.size == 2 && pair.pages.all { it.size == 1 })
                val documents = pair.pages.flatten()
                require(documents.map { it.id } == pairIds && document in documents)
                require(codec.pair(current, documents) == value)
                observedRevision = current.revision
                if (document.version <= boundary.revision && document.id == pairIds.first()) {
                    bindings += value
                }
            }
            val current =
                requireNotNull(replicas.observePublishedCheckpoint(scope, partition).first())
            codec.validate(current)
            check(
                current.handle.generation == boundary.generation &&
                    current.revision >= observedRevision
            )
            check(replicas.observePublishedCheckpoint(scope, partition).first() == current)
            val next =
                if (raw.documents.size > limit) {
                    CollaborationSpaceRoomBindingCursor(boundary, raw.documents[limit - 1].id)
                } else null
            CollaborationSpaceRoomBindingPage(
                boundary,
                bindings.toList(),
                next,
                current.revision > boundary.revision,
            )
        }
    }

    /** Finite exact reads only. The second published transaction authenticates both indexes. */
    private suspend fun lookup(
        access: CollaborationSpaceLocalAccess,
        select: suspend (CollaborationSpaceRoomBindingCodec) -> String,
    ): CollaborationSpaceRoomBinding? {
        val scope = spaceRoomBindingScope(access)
        val partition = spaceRoomBindingPartition(access)
        if (replicas.observePublishedCheckpoint(scope, partition).first() == null) return null
        return keys.use { content, index ->
            val codec = CollaborationSpaceRoomBindingCodec(access, cipher, content, index)
            val lookupId = select(codec)
            val page = replicas.observeDocument(scope, partition, lookupId).first()
            val checkpoint = page.checkpoint
            if (checkpoint == null) {
                require(page.documents.isEmpty())
                return@use null
            }
            codec.validate(checkpoint)
            require(page.documents.size <= 1 && page.documents.all { it.id == lookupId })
            val raw = page.documents.singleOrNull() ?: return@use null
            val value = codec.decode(checkpoint, raw)
            val ids = codec.ids(value)
            val pair =
                replicas
                    .observeQueries(scope, partition, ids.map { ReplicaQuery(tag = it, limit = 2) })
                    .first()
            val current = requireNotNull(pair.checkpoint)
            codec.validate(current)
            require(
                current.handle.generation == checkpoint.handle.generation &&
                    current.revision >= checkpoint.revision
            )
            require(pair.pages.size == 2 && pair.pages.all { it.size == 1 })
            val documents = pair.pages.flatten()
            require(documents.map { it.id } == ids && raw in documents)
            require(codec.pair(current, documents) == value)
            check(replicas.observePublishedCheckpoint(scope, partition).first() == current)
            value
        }
    }
}
