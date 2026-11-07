@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.history.CollaborationHistoryKeys
import dev.fajar.starter.collaboration.data.identity.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceConversationMetadataRepository
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.storage.safeStorageCall
import kotlin.uuid.Uuid
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import org.koin.core.annotation.*

@Single
class EncryptedCollaborationSpaceConversationMetadataRepository(
    private val replicas: ReplicaStore,
    private val cipher: ContentCipher,
    @Named("collaboration-history-content-key") private val credentials: AtomicCredentialStore,
) : CollaborationSpaceConversationMetadataRepository {
    private val keys = CollaborationHistoryKeys(replicas, cipher, credentials)

    override suspend fun getById(access: CollaborationSpaceLocalAccess, conversationId: String) =
        safeStorageCall {
            lookup(access, "conversation", conversationId)
        }

    override suspend fun findByPeer(access: CollaborationSpaceLocalAccess, peerAccountId: String) =
        safeStorageCall {
            require(peerAccountId != access.accountId)
            lookup(access, "peer", peerAccountId)
        }

    override suspend fun retain(
        access: CollaborationSpaceLocalAccess,
        conversation: CollaborationSpaceConversation,
    ) = safeStorageCall {
        val scope = spaceConversationMetadataScope(access)
        val partition = spaceConversationMetadataPartition(access)
        require(
            conversation.owner.localDirectoryAccess() == access &&
                validCollaborationSpaceConversation(conversation)
        )
        val value = conversation.localMetadata()
        require(validCollaborationSpaceConversationMetadata(access, value))
        val existing = replicas.checkpoint(scope, partition)
        keys.use(create = existing == null) { content, index ->
            val codec = CollaborationSpaceConversationMetadataCodec(access, cipher, content, index)
            val ids = codec.ids(value)
            val page = replicas.workingDocuments(scope, partition, ids)
            val checkpoint = page.checkpoint
            if (checkpoint == null) {
                require(page.documents.isEmpty())
                val generation = Uuid.random().toString()
                val snapshot =
                    ReplicaSnapshot(
                        generation,
                        codec.checkpoint(generation, 0),
                        codec.documents(generation, 0, value),
                    )
                currentCoroutineContext().ensureActive()
                return@use replicas.replace(scope, partition, null, snapshot)
            }
            codec.validate(checkpoint)
            require(page.documents.all { it.id in ids })
            if (page.documents.isNotEmpty()) {
                val same = codec.pair(checkpoint, page.documents) == value
                // Recheck ownership/revision after suspended decryption without writing or
                // retrying.
                return@use same && replicas.checkpoint(scope, partition) == checkpoint
            }
            require(checkpoint.revision < 9_007_199_254_740_991L)
            val revision = checkpoint.revision + 1
            val payload = codec.checkpoint(checkpoint.handle.generation, revision)
            val documents = codec.documents(checkpoint.handle.generation, revision, value)
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

    /** Finite exact reads only. The second published transaction authenticates both indexes. */
    private suspend fun lookup(
        access: CollaborationSpaceLocalAccess,
        kind: String,
        id: String,
    ): CollaborationSpaceConversationMetadata? {
        val scope = spaceConversationMetadataScope(access)
        val partition = spaceConversationMetadataPartition(access)
        require(validCollaborationSpaceConversationId(id))
        if (replicas.observePublishedCheckpoint(scope, partition).first() == null) return null
        return keys.use { content, index ->
            val codec = CollaborationSpaceConversationMetadataCodec(access, cipher, content, index)
            val lookupId = codec.lookupId(kind, id)
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
