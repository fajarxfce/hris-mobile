@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.data.recipients.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationRecipientObservationRepository
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.storage.*
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.koin.core.annotation.*

@Single
class EncryptedCollaborationRecipientObservationRepository(
    private val replicas: ReplicaStore,
    private val cipher: ContentCipher,
    @Named("collaboration-history-content-key") private val credentials: AtomicCredentialStore,
) : CollaborationRecipientObservationRepository {
    private val keys = CollaborationHistoryKeys(replicas, cipher, credentials)

    override fun observe(access: CollaborationLocalAccess, conversationId: String) =
        safeStorageFlow(
                flow {
                    require(validCollaborationMessageId(conversationId))
                    emitAll(
                        replicas.observeDocument(
                            historyScope(access),
                            recipientObservationPartition(access),
                            conversationId,
                        )
                    )
                },
                mapValue = { decode(access, conversationId, it) },
            )
            .flowOn(Dispatchers.Default)

    override suspend fun get(access: CollaborationLocalAccess, conversationId: String) =
        withContext(Dispatchers.Default) {
            safeStorageCall {
                    require(validCollaborationMessageId(conversationId))
                    val scope = historyScope(access)
                    val partition = recipientObservationPartition(access)
                    val page = replicas.observeDocument(scope, partition, conversationId).first()
                    val result = decode(access, conversationId, page)
                    result to (replicas.checkpoint(scope, partition) == page.checkpoint)
                }
                .stableRead()
        }

    // A concurrent observation write can advance the shared partition after a valid read. It is
    // retryable contention, distinct from malformed ciphertext or inaccessible key material.
    private fun <T> AppResult<Pair<T, Boolean>>.stableRead(): AppResult<T> =
        when (this) {
            is AppResult.Failed -> this
            is AppResult.Success ->
                if (value.second) AppResult.Success(value.first) else busyObservation()
        }

    private fun AppResult<Boolean?>.completedWrite(): AppResult<Boolean> =
        when (this) {
            is AppResult.Failed -> this
            is AppResult.Success -> value?.let { AppResult.Success(it) } ?: busyObservation()
        }

    private fun busyObservation() =
        AppResult.Failed(
            Failure(
                FailureKind.Conflict,
                "The recipient observation changed.",
                code = "client.collaboration_recipient_observation_busy",
            )
        )

    override suspend fun replace(
        access: CollaborationLocalAccess,
        expected: CollaborationRecipientObservation?,
        replacement: CollaborationRecipientObservation,
    ) =
        withContext(Dispatchers.Default) {
            safeStorageCall<Boolean?> {
                    require(validCollaborationRecipientObservation(replacement))
                    require(
                        expected == null ||
                            (validCollaborationRecipientObservation(expected) &&
                                expected.conversationId == replacement.conversationId)
                    )
                    val scope = historyScope(access)
                    val partition = recipientObservationPartition(access)
                    // Technical CAS contention from another document must not drop this mutation.
                    // Every attempt rereads this exact target; a changed target is never
                    // overwritten.
                    repeat(3) {
                        currentCoroutineContext().ensureActive()
                        val page =
                            replicas
                                .observeDocument(scope, partition, replacement.conversationId)
                                .first()
                        val current = decode(access, replacement.conversationId, page)
                        if (replicas.checkpoint(scope, partition) != page.checkpoint) return@repeat
                        if (current == replacement) return@safeStorageCall true
                        if (current != expected) return@safeStorageCall false
                        require(current?.revision != replacement.revision)
                        val written =
                            keys.use(create = page.checkpoint == null) { content, _ ->
                                val codec =
                                    CollaborationRecipientObservationCodec(access, cipher, content)
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
                        if (written) return@safeStorageCall true
                    }
                    // Exhausted technical retries do not prove that the target observation changed.
                    null
                }
                .completedWrite()
        }

    private suspend fun decode(
        access: CollaborationLocalAccess,
        conversationId: String,
        page: ReplicaPage,
    ): CollaborationRecipientObservation? {
        val checkpoint = page.checkpoint
        if (checkpoint == null) {
            require(page.documents.isEmpty())
            return null
        }
        return keys.use { content, _ ->
            val codec = CollaborationRecipientObservationCodec(access, cipher, content)
            codec.checkpoint(checkpoint)
            require(page.documents.size <= 1)
            page.documents.singleOrNull()?.let {
                require(it.id == conversationId)
                codec.decode(checkpoint, it)
            }
        }
    }
}
