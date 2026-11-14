@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.drafts.*
import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceRecipientDraftRepository
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.storage.*
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.koin.core.annotation.*

@Single
class EncryptedCollaborationSpaceRecipientDraftRepository(
    private val replicas: ReplicaStore,
    private val cipher: ContentCipher,
    @Named("collaboration-history-content-key") private val credentials: AtomicCredentialStore,
) : CollaborationSpaceRecipientDraftRepository {
    private val keys = CollaborationHistoryKeys(replicas, cipher, credentials)

    override fun observe(access: CollaborationSpaceLocalAccess, peerAccountId: String) =
        safeStorageFlow(
                flow {
                    require(validCollaborationSpaceRecipientCoordinates(access, peerAccountId))
                    emitAll(
                        replicas.observeDocument(
                            spaceHistoryScope(access),
                            recipientDraftPartition(access),
                            peerAccountId,
                        )
                    )
                },
                mapValue = { decode(access, peerAccountId, it) },
            )
            .flowOn(Dispatchers.Default)

    override suspend fun get(
        access: CollaborationSpaceLocalAccess,
        peerAccountId: String,
    ): AppResult<CollaborationSpaceRecipientDraft?> =
        withContext(Dispatchers.Default) {
            val result = safeStorageCall {
                require(validCollaborationSpaceRecipientCoordinates(access, peerAccountId))
                val scope = spaceHistoryScope(access)
                val partition = recipientDraftPartition(access)
                val page = replicas.observeDocument(scope, partition, peerAccountId).first()
                val retained = decode(access, peerAccountId, page)
                retained to (replicas.checkpoint(scope, partition) == page.checkpoint)
            }
            when (result) {
                is AppResult.Failed -> result
                is AppResult.Success ->
                    if (result.value.second) AppResult.Success(result.value.first)
                    else busyRecipientDraft()
            }
        }

    override suspend fun replace(
        access: CollaborationSpaceLocalAccess,
        expected: CollaborationSpaceRecipientDraft?,
        replacement: CollaborationSpaceRecipientDraft,
    ): AppResult<Boolean> =
        withContext(Dispatchers.Default) {
            val result =
                safeStorageCall<Boolean?> {
                    require(
                        validCollaborationSpaceRecipientDraftChange(access, expected, replacement)
                    )
                    val scope = spaceHistoryScope(access)
                    val partition = recipientDraftPartition(access)
                    // A different recipient may move the shared checkpoint; re-read this exact
                    // target.
                    repeat(3) {
                        currentCoroutineContext().ensureActive()
                        val page =
                            replicas
                                .observeDocument(scope, partition, replacement.peerAccountId)
                                .first()
                        val current = decode(access, replacement.peerAccountId, page)
                        if (replicas.checkpoint(scope, partition) != page.checkpoint) return@repeat
                        if (current == replacement) return@safeStorageCall true
                        if (current != expected) return@safeStorageCall false
                        require(current?.revision != replacement.revision)
                        val stored =
                            keys.use(create = page.checkpoint == null) { content, _ ->
                                val codec =
                                    CollaborationSpaceRecipientDraftCodec(access, cipher, content)
                                val raw = page.checkpoint
                                val generation = raw?.handle?.generation ?: Uuid.random().toString()
                                val revision = raw?.revision?.plus(1) ?: 0L
                                require(revision in 0 until 9_007_199_254_740_991L)
                                val checkpoint = codec.checkpoint(generation, revision)
                                val document = codec.encode(generation, revision, replacement)
                                currentCoroutineContext().ensureActive()
                                if (raw == null)
                                    replicas.replace(
                                        scope,
                                        partition,
                                        null,
                                        ReplicaSnapshot(generation, checkpoint, listOf(document)),
                                    )
                                else
                                    replicas.commit(
                                        raw.handle,
                                        raw.revision,
                                        checkpoint,
                                        listOf(document),
                                        true,
                                    )
                            }
                        if (stored) return@safeStorageCall true
                    }
                    // Exhausted checkpoint contention does not prove another writer changed this
                    // draft.
                    null
                }
            when (result) {
                is AppResult.Failed -> result
                is AppResult.Success ->
                    result.value?.let { AppResult.Success(it) } ?: busyRecipientDraft()
            }
        }

    private suspend fun decode(
        access: CollaborationSpaceLocalAccess,
        peerAccountId: String,
        page: ReplicaPage,
    ): CollaborationSpaceRecipientDraft? {
        // Raw test/adapter collections may be mutable; detach before cipher suspension.
        require(page.documents.size <= 1)
        val documents =
            page.documents.take(2).map {
                require(it.tags.isEmpty())
                it.copy(tags = emptySet())
            }
        require(documents.size <= 1)
        val checkpoint = page.checkpoint
        if (checkpoint == null) {
            require(documents.isEmpty())
            return null
        }
        return keys.use { content, _ ->
            val codec = CollaborationSpaceRecipientDraftCodec(access, cipher, content)
            codec.checkpoint(checkpoint)
            documents.singleOrNull()?.let {
                require(it.id == peerAccountId)
                codec.decode(checkpoint, it)
            }
        }
    }
}

private fun busyRecipientDraft() =
    AppResult.Failed(
        Failure(
            FailureKind.Conflict,
            "The draft is busy. Try again.",
            code = "client.collaboration_draft_busy",
        )
    )
