@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.drafts.*
import dev.fajar.starter.collaboration.data.drafts.CollaborationComposerSubmissionCodec as SubmissionCodec
import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.entities.CollaborationComposerHandoffResult as HandoffResult
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationComposerOutboxRepository
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceComposerOutboxRepository
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
) : CollaborationComposerOutboxRepository, CollaborationSpaceComposerOutboxRepository {
    private val keys = CollaborationHistoryKeys(replicas, cipher, credentials)

    override suspend fun handoff(
        access: CollaborationLocalAccess,
        submission: CollaborationComposerSubmission,
        expected: CollaborationDraft?,
    ) = handoff(CollaborationComposerCoordinates.Company(access), submission, expected)

    override suspend fun get(access: CollaborationLocalAccess, id: String) =
        get(CollaborationComposerCoordinates.Company(access), id)

    override fun observe(access: CollaborationLocalAccess) =
        observe(CollaborationComposerCoordinates.Company(access))

    override suspend fun hold(
        access: CollaborationLocalAccess,
        expected: CollaborationComposerSubmission,
    ) = hold(CollaborationComposerCoordinates.Company(access), expected)

    override suspend fun pending(access: CollaborationLocalAccess, after: String?, limit: Int) =
        pending(CollaborationComposerCoordinates.Company(access), after, limit)

    override suspend fun remove(
        access: CollaborationLocalAccess,
        expected: CollaborationComposerSubmission,
    ) = remove(CollaborationComposerCoordinates.Company(access), expected)

    override suspend fun handoff(
        access: CollaborationSpaceLocalAccess,
        submission: CollaborationComposerSubmission,
        expected: CollaborationDraft?,
    ) = handoff(CollaborationComposerCoordinates.Space(access), submission, expected)

    override suspend fun get(access: CollaborationSpaceLocalAccess, id: String) =
        get(CollaborationComposerCoordinates.Space(access), id)

    override fun observe(access: CollaborationSpaceLocalAccess) =
        observe(CollaborationComposerCoordinates.Space(access))

    override suspend fun hold(
        access: CollaborationSpaceLocalAccess,
        expected: CollaborationComposerSubmission,
    ) = hold(CollaborationComposerCoordinates.Space(access), expected)

    override suspend fun pending(
        access: CollaborationSpaceLocalAccess,
        after: String?,
        limit: Int,
    ) = pending(CollaborationComposerCoordinates.Space(access), after, limit)

    override suspend fun remove(
        access: CollaborationSpaceLocalAccess,
        expected: CollaborationComposerSubmission,
    ) = remove(CollaborationComposerCoordinates.Space(access), expected)

    private suspend fun handoff(
        coordinates: CollaborationComposerCoordinates,
        submission: CollaborationComposerSubmission,
        expected: CollaborationDraft?,
    ): AppResult<HandoffResult> =
        measureAppResult(PerformanceOperation.CommandHandoff) {
            withContext(Dispatchers.Default) {
                safeStorageCall {
                        require(validCollaborationComposerHandoff(submission, expected))
                        val scope = coordinates.scope
                        val partition = coordinates.partition
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
                                    val drafts = CollaborationDraftCodec(coordinates, cipher, key)
                                    val outbox = SubmissionCodec(coordinates, cipher, key)
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
                                    val wake =
                                        if (coordinates is CollaborationComposerCoordinates.Space)
                                            WorkWakeRequest(
                                                CollaborationSpaceRecoveryWake.WORK_KEY,
                                                Uuid.random().toString(),
                                                SPACE_RECOVERY_WAKE_MAX_KEYS,
                                            )
                                        else null
                                    val written =
                                        publishHandoff(
                                            coordinates,
                                            checkpoint,
                                            ReplicaSnapshot(generation, next, documents),
                                            wake,
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

    /** Both encrypted documents and their wake publish through one conditional storage mutation. */
    private suspend fun publishHandoff(
        coordinates: CollaborationComposerCoordinates,
        expected: ReplicaCheckpoint?,
        snapshot: ReplicaSnapshot,
        wake: WorkWakeRequest?,
    ): Boolean {
        currentCoroutineContext().ensureActive()
        return if (expected == null)
            replicas.replace(coordinates.scope, coordinates.partition, null, snapshot, wake)
        else
            replicas.commit(
                expected.handle,
                expected.revision,
                snapshot.checkpoint,
                snapshot.documents,
                true,
                wake,
            )
    }

    // Checkpoint and documents belong to one coherent observation. A different editor advancing
    // the partition does not invalidate these immutable originals; mutations still use exact CAS.
    private suspend fun get(
        coordinates: CollaborationComposerCoordinates,
        id: String,
    ): AppResult<CollaborationComposerSubmission?> =
        withContext(Dispatchers.Default) {
            safeStorageCall {
                require(validCollaborationMessageId(id))
                val scope = coordinates.scope
                val partition = coordinates.partition
                val page = replicas.observeDocument(scope, partition, id).first()
                decode(coordinates, id, page)
            }
        }

    private fun observe(
        coordinates: CollaborationComposerCoordinates
    ): Flow<AppResult<List<CollaborationComposerOutboxEntry>>> =
        flow {
                emitAll(
                    replicas.observeQueries(
                        coordinates.scope,
                        coordinates.partition,
                        listOf(
                            ReplicaQuery(COMPOSER_OUTBOX_TAG, limit = 129),
                            ReplicaQuery(COMPOSER_HELD_TAG, limit = 129),
                        ),
                    )
                )
            }
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
                            CollaborationDraftCodec(coordinates, cipher, key).checkpoint(checkpoint)
                            val codec = SubmissionCodec(coordinates, cipher, key)
                            documents
                                .map { codec.decodeEntry(checkpoint, it) }
                                .sortedBy { it.submission.id }
                        }
                }
            }
            .catch { error -> emit(safeStorageCall { throw error }) }
            .flowOn(Dispatchers.Default)
            .distinctUntilChanged()

    private suspend fun hold(
        coordinates: CollaborationComposerCoordinates,
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
                                    coordinates.scope,
                                    coordinates.partition,
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
                                val drafts = CollaborationDraftCodec(coordinates, cipher, key)
                                drafts.checkpoint(checkpoint)
                                val codec = SubmissionCodec(coordinates, cipher, key)
                                val document = page.documents.singleOrNull() ?: return@use false
                                val current = codec.decodeEntry(checkpoint, document)
                                if (
                                    replicas.checkpoint(coordinates.scope, coordinates.partition) !=
                                        checkpoint
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

    private suspend fun pending(
        coordinates: CollaborationComposerCoordinates,
        after: String?,
        limit: Int,
    ): AppResult<CollaborationComposerSubmissionPage> =
        withContext(Dispatchers.Default) {
            safeStorageCall {
                require(limit in 1..8 && (after == null || validCollaborationMessageId(after)))
                val scope = coordinates.scope
                val partition = coordinates.partition
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
                            CollaborationDraftCodec(coordinates, cipher, key).checkpoint(checkpoint)
                            require(documents.size <= limit + 1)
                            val codec = SubmissionCodec(coordinates, cipher, key)
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

    private suspend fun remove(
        coordinates: CollaborationComposerCoordinates,
        expected: CollaborationComposerSubmission,
    ): AppResult<Boolean> =
        withContext(Dispatchers.Default) {
            safeStorageCall {
                    require(validCollaborationComposerSubmission(expected))
                    val scope = coordinates.scope
                    val partition = coordinates.partition
                    repeat(3) {
                        currentCoroutineContext().ensureActive()
                        val page = replicas.observeDocument(scope, partition, expected.id).first()
                        val retained = decode(coordinates, expected.id, page)
                        if (replicas.checkpoint(scope, partition) != page.checkpoint) return@repeat
                        if (retained != expected) return@safeStorageCall false
                        val checkpoint = requireNotNull(page.checkpoint)
                        val revision = checkpoint.revision + 1
                        require(revision in 0 until 9_007_199_254_740_991L)
                        val next =
                            keys.use { key, _ ->
                                CollaborationDraftCodec(coordinates, cipher, key)
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
        coordinates: CollaborationComposerCoordinates,
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
            CollaborationDraftCodec(coordinates, cipher, key).checkpoint(checkpoint)
            page.documents.singleOrNull()?.let {
                require(it.id == id)
                SubmissionCodec(coordinates, cipher, key).decode(checkpoint, it)
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
