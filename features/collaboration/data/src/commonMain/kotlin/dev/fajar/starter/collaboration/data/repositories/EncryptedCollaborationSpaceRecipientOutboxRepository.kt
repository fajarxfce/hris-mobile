@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.drafts.*
import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceRecipientOutboxRepository
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.storage.*
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.koin.core.annotation.*

@Single
class EncryptedCollaborationSpaceRecipientOutboxRepository(
    private val replicas: ReplicaStore,
    private val cipher: ContentCipher,
    @Named("collaboration-history-content-key") private val credentials: AtomicCredentialStore,
) : CollaborationSpaceRecipientOutboxRepository {
    private val keys = CollaborationHistoryKeys(replicas, cipher, credentials)

    override suspend fun handoff(
        access: CollaborationSpaceLocalAccess,
        expected: CollaborationSpaceRecipientDraft?,
        submission: CollaborationSpaceRecipientSubmission,
    ): AppResult<CollaborationComposerHandoffResult> =
        withContext(Dispatchers.Default) {
            val result =
                safeStorageCall<CollaborationComposerHandoffResult?> {
                    require(validCollaborationSpaceRecipientHandoff(access, expected, submission))
                    val scope = spaceHistoryScope(access)
                    val partition = recipientDraftPartition(access)
                    val id = recipientSubmissionId(submission.id)
                    repeat(3) {
                        currentCoroutineContext().ensureActive()
                        val raw =
                            replicas.workingDocuments(
                                scope,
                                partition,
                                listOf(submission.original.peerAccountId, id),
                            )
                        val documents = capture(raw.documents, 2)
                        val checkpoint = raw.checkpoint
                        if (checkpoint?.published == false)
                            return@safeStorageCall CollaborationComposerHandoffResult.CHANGED
                        require(checkpoint != null || documents.isEmpty())
                        val outcome =
                            keys.use(create = checkpoint == null) { key, _ ->
                                val codec =
                                    CollaborationSpaceRecipientHandoffCodec(access, cipher, key)
                                val source = codec.source(checkpoint, documents, submission)
                                if (replicas.checkpoint(scope, partition) != checkpoint)
                                    return@use null
                                if (source.retained != null)
                                    return@use if (source.retained == submission)
                                        CollaborationComposerHandoffResult.RETAINED
                                    else CollaborationComposerHandoffResult.CHANGED
                                if (source.editor != expected)
                                    return@use CollaborationComposerHandoffResult.CHANGED
                                val inventory =
                                    replicas
                                        .observeQueries(
                                            scope,
                                            partition,
                                            listOf(ReplicaQuery(RECIPIENT_OUTBOX_TAG, limit = 129)),
                                        )
                                        .first()
                                require(inventory.pages.size == 1)
                                val active = capture(inventory.pages.single(), 129)
                                require(
                                    active.all {
                                        it.tags == setOf(RECIPIENT_OUTBOX_TAG) &&
                                            it.id ==
                                                recipientSubmissionId(
                                                    it.id.removePrefix("submission:")
                                                ) &&
                                            it.sortKey == it.id
                                    }
                                )
                                if (inventory.checkpoint != checkpoint) return@use null
                                if (active.size >= 128)
                                    return@use CollaborationComposerHandoffResult.CAPACITY_EXCEEDED
                                val snapshot = codec.replacement(checkpoint, submission)
                                val wake =
                                    WorkWakeRequest(
                                        CollaborationSpaceRecoveryWake.WORK_KEY,
                                        Uuid.random().toString(),
                                        SPACE_RECOVERY_WAKE_MAX_KEYS,
                                    )
                                currentCoroutineContext().ensureActive()
                                val committed =
                                    if (checkpoint == null)
                                        replicas.replace(scope, partition, null, snapshot, wake)
                                    else
                                        replicas.commit(
                                            checkpoint.handle,
                                            checkpoint.revision,
                                            snapshot.checkpoint,
                                            snapshot.documents,
                                            true,
                                            wake,
                                        )
                                if (committed) CollaborationComposerHandoffResult.STORED else null
                            }
                        if (outcome != null) return@safeStorageCall outcome
                    }
                    null
                }
            when (result) {
                is AppResult.Failed -> result
                is AppResult.Success ->
                    result.value?.let { AppResult.Success(it) } ?: busyRecipientOutbox()
            }
        }

    override suspend fun get(
        access: CollaborationSpaceLocalAccess,
        id: String,
    ): AppResult<CollaborationSpaceRecipientSubmission?> =
        withContext(Dispatchers.Default) {
            val result = safeStorageCall {
                spaceHistoryScope(access)
                val rawId = recipientSubmissionId(id)
                val page =
                    replicas
                        .observeDocument(
                            spaceHistoryScope(access),
                            recipientDraftPartition(access),
                            rawId,
                        )
                        .first()
                val documents = capture(page.documents, 1)
                require(documents.all { it.id == rawId })
                val values = decode(access, page.checkpoint, documents)
                values.singleOrNull() to
                    (replicas.checkpoint(
                        spaceHistoryScope(access),
                        recipientDraftPartition(access),
                    ) == page.checkpoint)
            }
            when (result) {
                is AppResult.Failed -> result
                is AppResult.Success ->
                    if (result.value.second) AppResult.Success(result.value.first)
                    else busyRecipientOutbox()
            }
        }

    override suspend fun pending(
        access: CollaborationSpaceLocalAccess,
        after: String?,
        limit: Int,
    ): AppResult<CollaborationSpaceRecipientSubmissionPage> =
        withContext(Dispatchers.Default) {
            val result = safeStorageCall {
                require(limit in 1..8)
                val scope = spaceHistoryScope(access)
                val partition = recipientDraftPartition(access)
                val anchor = after?.let(::recipientSubmissionId)
                val raw =
                    replicas
                        .observeQueries(
                            scope,
                            partition,
                            listOf(
                                ReplicaQuery(
                                    RECIPIENT_OUTBOX_TAG,
                                    after = anchor,
                                    limit = limit + 1,
                                )
                            ),
                        )
                        .first()
                require(raw.pages.size == 1)
                val documents = capture(raw.pages.single(), limit + 1)
                require(documents.zipWithNext().all { (a, b) -> a.sortKey < b.sortKey })
                require(anchor == null || documents.all { it.sortKey > anchor })
                val values = decode(access, raw.checkpoint, documents)
                val selected = values.take(limit)
                CollaborationSpaceRecipientSubmissionPage(
                    selected,
                    if (values.size > limit) selected.last().id else null,
                ) to (replicas.checkpoint(scope, partition) == raw.checkpoint)
            }
            when (result) {
                is AppResult.Failed -> result
                is AppResult.Success ->
                    if (result.value.second) AppResult.Success(result.value.first)
                    else busyRecipientOutbox()
            }
        }

    /**
     * Freeze bounded adapter collections before any suspension, including the content-key lease.
     */
    private fun capture(documents: List<ReplicaDocument>, limit: Int): List<ReplicaDocument> {
        require(documents.size <= limit)
        return documents
            .take(limit + 1)
            .map {
                require(it.tags.isEmpty() || it.tags == setOf(RECIPIENT_OUTBOX_TAG))
                it.copy(tags = if (it.tags.isEmpty()) emptySet() else setOf(RECIPIENT_OUTBOX_TAG))
            }
            .also { require(it.size <= limit && it.map { d -> d.id }.distinct().size == it.size) }
    }

    private suspend fun decode(
        access: CollaborationSpaceLocalAccess,
        checkpoint: ReplicaCheckpoint?,
        documents: List<ReplicaDocument>,
    ): List<CollaborationSpaceRecipientSubmission> {
        if (checkpoint == null) {
            require(documents.isEmpty())
            return emptyList()
        }
        return keys.use { key, _ ->
            CollaborationSpaceRecipientDraftCodec(access, cipher, key).checkpoint(checkpoint)
            val codec = CollaborationSpaceRecipientSubmissionCodec(access, cipher, key)
            documents.map { codec.decode(checkpoint, it) }
        }
    }
}

private fun busyRecipientOutbox() =
    AppResult.Failed(
        Failure(
            FailureKind.Conflict,
            "The message could not be saved. Try again.",
            code = "client.collaboration_composer_busy",
        )
    )
