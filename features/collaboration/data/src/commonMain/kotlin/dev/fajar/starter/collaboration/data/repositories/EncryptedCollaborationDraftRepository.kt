@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.drafts.*
import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationDraftRepository
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceDraftRepository
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.storage.*
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.koin.core.annotation.*

@Single
class EncryptedCollaborationDraftRepository(
    private val replicas: ReplicaStore,
    private val cipher: ContentCipher,
    @Named("collaboration-history-content-key") private val credentials: AtomicCredentialStore,
) : CollaborationDraftRepository, CollaborationSpaceDraftRepository {
    private val keys = CollaborationHistoryKeys(replicas, cipher, credentials)

    override fun observe(access: CollaborationLocalAccess, conversationId: String) =
        observe(CollaborationComposerCoordinates.Company(access), conversationId)

    override suspend fun get(access: CollaborationLocalAccess, conversationId: String) =
        get(CollaborationComposerCoordinates.Company(access), conversationId)

    override suspend fun replace(
        access: CollaborationLocalAccess,
        expected: CollaborationDraft?,
        replacement: CollaborationDraft,
    ) = replace(CollaborationComposerCoordinates.Company(access), expected, replacement)

    override suspend fun prepared(
        access: CollaborationLocalAccess,
        afterConversationId: String?,
        limit: Int,
    ) = prepared(CollaborationComposerCoordinates.Company(access), afterConversationId, limit)

    override fun observe(access: CollaborationSpaceLocalAccess, conversationId: String) =
        observe(CollaborationComposerCoordinates.Space(access), conversationId)

    override suspend fun get(access: CollaborationSpaceLocalAccess, conversationId: String) =
        get(CollaborationComposerCoordinates.Space(access), conversationId)

    override suspend fun replace(
        access: CollaborationSpaceLocalAccess,
        expected: CollaborationDraft?,
        replacement: CollaborationDraft,
    ) = replace(CollaborationComposerCoordinates.Space(access), expected, replacement)

    private fun observe(coordinates: CollaborationComposerCoordinates, conversationId: String) =
        safeStorageFlow(
                flow {
                    require(validCollaborationMessageId(conversationId))
                    emitAll(
                        replicas.observeDocument(
                            coordinates.scope,
                            coordinates.partition,
                            conversationId,
                        )
                    )
                },
                mapValue = { decode(coordinates, conversationId, it) },
            )
            .flowOn(Dispatchers.Default)

    private suspend fun get(coordinates: CollaborationComposerCoordinates, conversationId: String) =
        withContext(Dispatchers.Default) {
            safeStorageCall {
                    require(validCollaborationMessageId(conversationId))
                    val scope = coordinates.scope
                    val partition = coordinates.partition
                    val page = replicas.observeDocument(scope, partition, conversationId).first()
                    val result = decode(coordinates, conversationId, page)
                    result to (replicas.checkpoint(scope, partition) == page.checkpoint)
                }
                .stableRead()
        }

    private suspend fun prepared(
        coordinates: CollaborationComposerCoordinates,
        afterConversationId: String?,
        limit: Int,
    ) =
        withContext(Dispatchers.Default) {
            safeStorageCall {
                    require(
                        limit in 1..8 &&
                            (afterConversationId == null ||
                                validCollaborationMessageId(afterConversationId))
                    )
                    val scope = coordinates.scope
                    val partition = coordinates.partition
                    val page =
                        replicas
                            .observeQueries(
                                scope,
                                partition,
                                listOf(
                                    ReplicaQuery(
                                        PREPARED_DRAFT_TAG,
                                        after = afterConversationId,
                                        limit = limit + 1,
                                    )
                                ),
                            )
                            .first()
                    val documents = page.pages.single()
                    val checkpoint = page.checkpoint
                    val drafts =
                        if (checkpoint == null) {
                            require(documents.isEmpty())
                            emptyList()
                        } else
                            keys.use { content, _ ->
                                val codec = CollaborationDraftCodec(coordinates, cipher, content)
                                codec.checkpoint(checkpoint)
                                require(documents.size <= limit + 1)
                                documents
                                    .map { codec.decode(checkpoint, it) }
                                    .also { values ->
                                        require(
                                            values.all {
                                                it.submissionId != null &&
                                                    !it.reviewRequired &&
                                                    (afterConversationId == null ||
                                                        it.conversationId > afterConversationId)
                                            }
                                        )
                                        require(
                                            values.map { it.conversationId } ==
                                                values.map { it.conversationId }.distinct().sorted()
                                        )
                                    }
                            }
                    val selected = drafts.take(limit)
                    CollaborationPreparedDraftPage(
                        selected,
                        if (drafts.size > limit) selected.last().conversationId else null,
                    ) to (replicas.checkpoint(scope, partition) == checkpoint)
                }
                .stableRead()
        }

    // A concurrent draft write can advance the shared partition after a valid read. It is
    // retryable contention, distinct from malformed ciphertext or inaccessible key material.
    private fun <T> AppResult<Pair<T, Boolean>>.stableRead(): AppResult<T> =
        when (this) {
            is AppResult.Failed -> this
            is AppResult.Success ->
                if (value.second) AppResult.Success(value.first) else busyDraft()
        }

    private fun AppResult<Boolean?>.completedWrite(): AppResult<Boolean> =
        when (this) {
            is AppResult.Failed -> this
            is AppResult.Success -> value?.let { AppResult.Success(it) } ?: busyDraft()
        }

    private fun busyDraft() =
        AppResult.Failed(
            Failure(
                FailureKind.Conflict,
                "The draft is busy. Try again.",
                code = "client.collaboration_draft_busy",
            )
        )

    private suspend fun replace(
        coordinates: CollaborationComposerCoordinates,
        expected: CollaborationDraft?,
        replacement: CollaborationDraft,
    ) =
        withContext(Dispatchers.Default) {
            safeStorageCall<Boolean?> {
                    require(validCollaborationDraft(replacement))
                    require(
                        expected == null ||
                            (validCollaborationDraft(expected) &&
                                expected.conversationId == replacement.conversationId)
                    )
                    val scope = coordinates.scope
                    val partition = coordinates.partition
                    // Technical CAS contention from another document must not drop this mutation.
                    // Every attempt rereads this exact target; a changed target is never
                    // overwritten.
                    repeat(3) {
                        currentCoroutineContext().ensureActive()
                        val page =
                            replicas
                                .observeDocument(scope, partition, replacement.conversationId)
                                .first()
                        val current = decode(coordinates, replacement.conversationId, page)
                        if (replicas.checkpoint(scope, partition) != page.checkpoint) return@repeat
                        if (current == replacement) return@safeStorageCall true
                        if (current != expected) return@safeStorageCall false
                        require(current?.revision != replacement.revision)
                        val written =
                            keys.use(create = page.checkpoint == null) { content, _ ->
                                val codec = CollaborationDraftCodec(coordinates, cipher, content)
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
                    // Exhausted technical retries do not prove that the target draft changed.
                    null
                }
                .completedWrite()
        }

    private suspend fun decode(
        coordinates: CollaborationComposerCoordinates,
        conversationId: String,
        page: ReplicaPage,
    ): CollaborationDraft? {
        val checkpoint = page.checkpoint
        if (checkpoint == null) {
            require(page.documents.isEmpty())
            return null
        }
        return keys.use { content, _ ->
            val codec = CollaborationDraftCodec(coordinates, cipher, content)
            codec.checkpoint(checkpoint)
            require(page.documents.size <= 1)
            page.documents.singleOrNull()?.let {
                require(it.id == conversationId)
                codec.decode(checkpoint, it)
            }
        }
    }
}
