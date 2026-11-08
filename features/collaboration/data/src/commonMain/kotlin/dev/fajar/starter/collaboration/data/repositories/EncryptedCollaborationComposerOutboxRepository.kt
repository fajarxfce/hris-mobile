@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.drafts.*
import dev.fajar.starter.collaboration.data.drafts.CollaborationComposerSubmissionCodec as SubmissionCodec
import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.entities.CollaborationComposerHandoffResult as HandoffResult
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationComposerOutboxRepository
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.observability.PerformanceOperation
import dev.fajar.starter.observability.measureAppResult
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.storage.safeStorageCall
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
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
        expected: CollaborationDraft?,
    ): AppResult<HandoffResult> =
        measureAppResult(PerformanceOperation.CommandHandoff) {
            withContext(Dispatchers.Default) {
                safeStorageCall {
                        require(validCollaborationComposerHandoff(submission, expected))
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
                                require(
                                    original.documents.isEmpty() && outgoing.documents.isEmpty()
                                )
                                if (expected != null) return@safeStorageCall HandoffResult.CHANGED
                            }
                            require(original.documents.size <= 1 && outgoing.documents.size <= 1)
                            val result =
                                keys.use(create = checkpoint == null) { key, _ ->
                                    val drafts = CollaborationDraftCodec(access, cipher, key)
                                    val outbox = SubmissionCodec(access, cipher, key)
                                    if (checkpoint != null) drafts.checkpoint(checkpoint)
                                    val retained =
                                        outgoing.documents.singleOrNull()?.let { existing ->
                                            require(existing.id == submission.id)
                                            outbox.decode(requireNotNull(checkpoint), existing)
                                        }
                                    if (replicas.checkpoint(scope, partition) != checkpoint)
                                        return@use null
                                    if (retained != null)
                                        return@use if (retained == submission)
                                            HandoffResult.RETAINED
                                        else HandoffResult.CHANGED
                                    val current =
                                        original.documents.singleOrNull()?.let {
                                            require(it.id == conversation)
                                            drafts.decode(requireNotNull(checkpoint), it)
                                        }
                                    if (replicas.checkpoint(scope, partition) != checkpoint)
                                        return@use null
                                    if (current != expected) return@use HandoffResult.CHANGED
                                    val pending =
                                        replicas
                                            .observeQueries(
                                                scope,
                                                partition,
                                                listOf(
                                                    ReplicaQuery(COMPOSER_OUTBOX_TAG, limit = 129),
                                                    ReplicaQuery(COMPOSER_HELD_TAG, limit = 129),
                                                ),
                                            )
                                            .first()
                                    if (pending.checkpoint != checkpoint) return@use null
                                    val pages = pending.pages
                                    require(pages.size == 2 && pages.all { it.size <= 129 })
                                    if (pages.sumOf { it.size } >= 128)
                                        return@use HandoffResult.CAPACITY_EXCEEDED
                                    val revision = checkpoint?.revision?.plus(1) ?: 0L
                                    require(revision in 0 until 9_007_199_254_740_991L)
                                    val generation =
                                        checkpoint?.handle?.generation ?: Uuid.random().toString()
                                    val nextDraft = submission.nextDraft
                                    val documents =
                                        listOf(
                                            drafts.encode(generation, revision, nextDraft),
                                            outbox.encode(generation, revision, submission),
                                        )
                                    val next = drafts.checkpoint(generation, revision)
                                    currentCoroutineContext().ensureActive()
                                    val written =
                                        if (checkpoint == null)
                                            replicas.replace(
                                                scope,
                                                partition,
                                                null,
                                                ReplicaSnapshot(generation, next, documents),
                                            )
                                        else
                                            replicas.commit(
                                                checkpoint.handle,
                                                checkpoint.revision,
                                                next,
                                                documents,
                                                true,
                                            )
                                    if (written) HandoffResult.STORED else null
                                }
                            if (result != null) return@safeStorageCall result
                        }
                        null
                    }
                    .stable()
            }
        }

    // Checkpoint and documents belong to one coherent observation. A different editor advancing
    // the partition does not invalidate these immutable originals; mutations still use exact CAS.
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
                decode(access, id, page)
            }
        }

    override fun observe(
        access: CollaborationLocalAccess
    ): Flow<AppResult<List<CollaborationComposerOutboxEntry>>> =
        replicas
            .observeQueries(
                historyScope(access),
                draftPartition(access),
                listOf(
                    ReplicaQuery(COMPOSER_OUTBOX_TAG, limit = 129),
                    ReplicaQuery(COMPOSER_HELD_TAG, limit = 129),
                ),
            )
            .map { page ->
                safeStorageCall {
                    require(page.pages.size == 2)
                    val documents = page.pages.flatten()
                    require(
                        documents.size <= 128 &&
                            documents.map { it.id }.distinct().size == documents.size
                    )
                    val checkpoint = page.checkpoint
                    if (checkpoint == null) {
                        require(documents.isEmpty())
                        emptyList()
                    } else
                        keys.use { key, _ ->
                            CollaborationDraftCodec(access, cipher, key).checkpoint(checkpoint)
                            val codec = SubmissionCodec(access, cipher, key)
                            documents
                                .map { codec.decodeEntry(checkpoint, it) }
                                .sortedBy { it.submission.id }
                        }
                }
            }
            .catch { error -> emit(safeStorageCall { throw error }) }
            .flowOn(Dispatchers.Default)
            .distinctUntilChanged()

    override suspend fun hold(
        access: CollaborationLocalAccess,
        expected: CollaborationComposerSubmission,
    ): AppResult<Boolean> =
        withContext(Dispatchers.Default) {
            safeStorageCall {
                    require(validCollaborationComposerSubmission(expected))
                    repeat(3) {
                        currentCoroutineContext().ensureActive()
                        val page =
                            replicas
                                .observeDocument(
                                    historyScope(access),
                                    draftPartition(access),
                                    expected.id,
                                )
                                .first()
                        val checkpoint = page.checkpoint
                        if (checkpoint == null) {
                            require(page.documents.isEmpty())
                            return@safeStorageCall false
                        }
                        require(page.documents.size <= 1)
                        val held =
                            keys.use { key, _ ->
                                val drafts = CollaborationDraftCodec(access, cipher, key)
                                drafts.checkpoint(checkpoint)
                                val codec = SubmissionCodec(access, cipher, key)
                                val document = page.documents.singleOrNull() ?: return@use false
                                val current = codec.decodeEntry(checkpoint, document)
                                if (
                                    replicas.checkpoint(
                                        historyScope(access),
                                        draftPartition(access),
                                    ) != checkpoint
                                )
                                    return@use null
                                if (current.submission != expected) return@use false
                                if (current.reviewRequired) return@use true
                                val revision = checkpoint.revision + 1
                                require(revision in 0 until 9_007_199_254_740_991L)
                                val next = drafts.checkpoint(checkpoint.handle.generation, revision)
                                val replacement =
                                    codec.encode(
                                        checkpoint.handle.generation,
                                        revision,
                                        expected,
                                        reviewRequired = true,
                                    )
                                currentCoroutineContext().ensureActive()
                                if (
                                    replicas.commit(
                                        checkpoint.handle,
                                        checkpoint.revision,
                                        next,
                                        listOf(replacement),
                                        true,
                                    )
                                )
                                    true
                                else null
                            }
                        if (held != null) return@safeStorageCall held
                    }
                    null
                }
                .stable()
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
                            val codec = SubmissionCodec(access, cipher, key)
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
                )
            }
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
                SubmissionCodec(access, cipher, key).decode(checkpoint, it)
            }
        }
    }

    private fun <T : Any> AppResult<T?>.stable(): AppResult<T> =
        when (this) {
            is AppResult.Failed -> this
            is AppResult.Success -> value?.let { AppResult.Success(it) } ?: busy()
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
