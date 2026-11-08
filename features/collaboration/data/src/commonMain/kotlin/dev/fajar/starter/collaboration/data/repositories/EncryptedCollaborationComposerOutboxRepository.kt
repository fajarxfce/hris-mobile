package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.drafts.*
import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationComposerOutboxRepository
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.storage.safeStorageCall
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.koin.core.annotation.*

@Single
class EncryptedCollaborationComposerOutboxRepository(
    private val replicas: ReplicaStore,
    private val cipher: ContentCipher,
    @Named("collaboration-history-content-key") private val credentials: AtomicCredentialStore,
) : CollaborationComposerOutboxRepository {
    private val keys = CollaborationHistoryKeys(replicas, cipher, credentials)

    override suspend fun handoff(
        access: CollaborationLocalAccess,
        submission: CollaborationComposerSubmission,
    ): AppResult<CollaborationComposerHandoffResult> =
        withContext(Dispatchers.Default) {
            safeStorageCall {
                    require(validCollaborationComposerSubmission(submission))
                    val scope = historyScope(access)
                    val partition = draftPartition(access)
                    val conversation = submission.original.conversationId
                    repeat(3) {
                        currentCoroutineContext().ensureActive()
                        val original =
                            replicas.observeDocument(scope, partition, conversation).first()
                        val outgoing =
                            replicas.observeDocument(scope, partition, submission.id).first()
                        if (original.checkpoint != outgoing.checkpoint) return@repeat
                        val checkpoint = original.checkpoint
                        if (checkpoint == null) {
                            require(original.documents.isEmpty() && outgoing.documents.isEmpty())
                            return@safeStorageCall CollaborationComposerHandoffResult.CHANGED
                        }
                        require(original.documents.size <= 1 && outgoing.documents.size <= 1)
                        val result =
                            keys.use { key, _ ->
                                val drafts = CollaborationDraftCodec(access, cipher, key)
                                val outbox =
                                    CollaborationComposerSubmissionCodec(access, cipher, key)
                                drafts.checkpoint(checkpoint)
                                val retained =
                                    outgoing.documents.singleOrNull()?.let { existing ->
                                        require(existing.id == submission.id)
                                        outbox.decode(checkpoint, existing)
                                    }
                                if (replicas.checkpoint(scope, partition) != checkpoint)
                                    return@use null
                                if (retained != null)
                                    return@use if (retained == submission)
                                        CollaborationComposerHandoffResult.RETAINED
                                    else CollaborationComposerHandoffResult.CHANGED
                                val current =
                                    original.documents.singleOrNull()?.let {
                                        require(it.id == conversation)
                                        drafts.decode(checkpoint, it)
                                    }
                                if (replicas.checkpoint(scope, partition) != checkpoint)
                                    return@use null
                                if (current != submission.original)
                                    return@use CollaborationComposerHandoffResult.CHANGED
                                val pending =
                                    replicas
                                        .observeQueries(
                                            scope,
                                            partition,
                                            listOf(ReplicaQuery(COMPOSER_OUTBOX_TAG, limit = 129)),
                                        )
                                        .first()
                                if (pending.checkpoint != checkpoint) return@use null
                                require(
                                    pending.pages.size == 1 && pending.pages.single().size <= 129
                                )
                                if (pending.pages.single().size >= 128)
                                    return@use CollaborationComposerHandoffResult.CAPACITY_EXCEEDED
                                val revision = checkpoint.revision + 1
                                require(revision in 0 until 9_007_199_254_740_991L)
                                val generation = checkpoint.handle.generation
                                val documents =
                                    listOf(
                                        drafts.encode(generation, revision, submission.nextDraft),
                                        outbox.encode(generation, revision, submission),
                                    )
                                val next = drafts.checkpoint(generation, revision)
                                currentCoroutineContext().ensureActive()
                                if (
                                    replicas.commit(
                                        checkpoint.handle,
                                        checkpoint.revision,
                                        next,
                                        documents,
                                        true,
                                    )
                                )
                                    CollaborationComposerHandoffResult.STORED
                                else null
                            }
                        if (result != null) return@safeStorageCall result
                    }
                    null
                }
                .stable()
        }

    override suspend fun get(
        access: CollaborationLocalAccess,
        id: String,
    ): AppResult<CollaborationComposerSubmission?> =
        withContext(Dispatchers.Default) {
            safeStorageCall {
                    require(validCollaborationMessageId(id))
                    val scope = historyScope(access)
                    val partition = draftPartition(access)
                    val page = replicas.observeDocument(scope, partition, id).first()
                    val value = decode(access, id, page)
                    value to (replicas.checkpoint(scope, partition) == page.checkpoint)
                }
                .stableRead()
        }

    override suspend fun pending(
        access: CollaborationLocalAccess,
        after: String?,
        limit: Int,
    ): AppResult<CollaborationComposerSubmissionPage> =
        withContext(Dispatchers.Default) {
            safeStorageCall {
                    require(limit in 1..8 && (after == null || validCollaborationMessageId(after)))
                    val scope = historyScope(access)
                    val partition = draftPartition(access)
                    val page =
                        replicas
                            .observeQueries(
                                scope,
                                partition,
                                listOf(ReplicaQuery(COMPOSER_OUTBOX_TAG, after, limit + 1)),
                            )
                            .first()
                    val documents = page.pages.single()
                    val checkpoint = page.checkpoint
                    val values =
                        if (checkpoint == null) {
                            require(documents.isEmpty())
                            emptyList()
                        } else
                            keys.use { key, _ ->
                                CollaborationDraftCodec(access, cipher, key).checkpoint(checkpoint)
                                require(documents.size <= limit + 1)
                                val codec =
                                    CollaborationComposerSubmissionCodec(access, cipher, key)
                                documents
                                    .map { codec.decode(checkpoint, it) }
                                    .also { submissions ->
                                        require(submissions.all { after == null || it.id > after })
                                        require(
                                            submissions.map { it.id } ==
                                                submissions.map { it.id }.distinct().sorted()
                                        )
                                    }
                            }
                    val selected = values.take(limit)
                    CollaborationComposerSubmissionPage(
                        selected,
                        if (values.size > limit) selected.last().id else null,
                    ) to (replicas.checkpoint(scope, partition) == checkpoint)
                }
                .stableRead()
        }

    override suspend fun remove(
        access: CollaborationLocalAccess,
        expected: CollaborationComposerSubmission,
    ): AppResult<Boolean> =
        withContext(Dispatchers.Default) {
            safeStorageCall {
                    require(validCollaborationComposerSubmission(expected))
                    val scope = historyScope(access)
                    val partition = draftPartition(access)
                    repeat(3) {
                        currentCoroutineContext().ensureActive()
                        val page = replicas.observeDocument(scope, partition, expected.id).first()
                        val retained = decode(access, expected.id, page)
                        if (replicas.checkpoint(scope, partition) != page.checkpoint) return@repeat
                        if (retained != expected) return@safeStorageCall false
                        val checkpoint = requireNotNull(page.checkpoint)
                        val revision = checkpoint.revision + 1
                        require(revision in 0 until 9_007_199_254_740_991L)
                        val next =
                            keys.use { key, _ ->
                                CollaborationDraftCodec(access, cipher, key)
                                    .checkpoint(checkpoint.handle.generation, revision)
                            }
                        currentCoroutineContext().ensureActive()
                        if (
                            replicas.commit(
                                checkpoint.handle,
                                checkpoint.revision,
                                next,
                                listOf(ReplicaDocument(expected.id, revision, expected.id, null)),
                                true,
                            )
                        )
                            return@safeStorageCall true
                    }
                    null
                }
                .stable()
        }

    private suspend fun decode(
        access: CollaborationLocalAccess,
        id: String,
        page: ReplicaPage,
    ): CollaborationComposerSubmission? {
        val checkpoint = page.checkpoint
        if (checkpoint == null) {
            require(page.documents.isEmpty())
            return null
        }
        require(page.documents.size <= 1)
        return keys.use { key, _ ->
            CollaborationDraftCodec(access, cipher, key).checkpoint(checkpoint)
            page.documents.singleOrNull()?.let {
                require(it.id == id)
                CollaborationComposerSubmissionCodec(access, cipher, key).decode(checkpoint, it)
            }
        }
    }

    private fun <T : Any> AppResult<T?>.stable(): AppResult<T> =
        when (this) {
            is AppResult.Failed -> this
            is AppResult.Success -> value?.let { AppResult.Success(it) } ?: busy()
        }

    private fun <T> AppResult<Pair<T, Boolean>>.stableRead(): AppResult<T> =
        when (this) {
            is AppResult.Failed -> this
            is AppResult.Success -> if (value.second) AppResult.Success(value.first) else busy()
        }

    private fun busy() =
        AppResult.Failed(
            Failure(
                FailureKind.Conflict,
                "The composer changed. Try again.",
                code = "client.collaboration_composer_busy",
            )
        )
}
