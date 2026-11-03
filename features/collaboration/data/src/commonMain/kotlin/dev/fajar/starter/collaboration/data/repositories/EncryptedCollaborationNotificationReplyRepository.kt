@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.history.CollaborationHistoryKeys
import dev.fajar.starter.collaboration.data.notifications.*
import dev.fajar.starter.collaboration.data.recovery.recoveryScope
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationNotificationReplyRepository
import dev.fajar.starter.collaboration.domain.repositories.CollaborationNotificationReplyResolutionRepository
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.storage.safeStorageCall
import dev.fajar.starter.storage.safeStorageFlow
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import org.koin.core.annotation.*

@Single
class EncryptedCollaborationNotificationReplyRepository(
    private val replicas: ReplicaStore,
    private val cipher: ContentCipher,
    @Named("collaboration-history-content-key") credentials: AtomicCredentialStore,
) : CollaborationNotificationReplyRepository, CollaborationNotificationReplyResolutionRepository {
    private val keys = CollaborationHistoryKeys(replicas, cipher, credentials)

    override fun observeChanges(access: CollaborationRecoveryAccess) =
        safeStorageFlow(
                flow {
                    emitAll(
                        replicas
                            .observePublishedCheckpoint(
                                recoveryScope(access),
                                NOTIFICATION_REPLY_PARTITION,
                            )
                            .distinctUntilChanged()
                            .map { Unit }
                            .onCompletion { cause ->
                                check(cause != null) { "Reply observation completed" }
                            }
                    )
                }
            )
            .flowOn(Dispatchers.Default)
            .buffer(0)

    override suspend fun retain(
        access: CollaborationRecoveryAccess,
        candidate: CollaborationNotificationReplyCandidate,
    ) = safeStorageCall {
        require(validCollaborationNotificationReplyCandidate(candidate))
        val parent = candidate.parent
        require(parent.audience == access.audience && parent.accountId == access.accountId)
        val raw = replicas.checkpoint(recoveryScope(access), NOTIFICATION_REPLY_PARTITION)
        keys.use { key, index ->
            val codec = CollaborationNotificationReplyCodec(access, cipher, key, index)
            val active = raw?.let { codec.checkpoint(it) } ?: emptyList()
            val id = codec.id(candidate)
            val reference = active.find { it.id == id }
            if (reference != null) {
                val retained = load(codec, requireNotNull(raw), reference)
                require(retained.candidate.copy(token = candidate.token) == candidate)
                return@use if (current(codec, raw))
                    CollaborationNotificationReplyRetention.Retained(retained)
                else CollaborationNotificationReplyRetention.Contended
            }
            if (active.size >= COLLABORATION_NOTIFICATION_REPLY_CAPACITY)
                return@use if (current(codec, raw))
                    CollaborationNotificationReplyRetention.CapacityExceeded
                else CollaborationNotificationReplyRetention.Contended
            val generation = raw?.handle?.generation ?: Uuid.random().toString()
            val revision = (raw?.revision ?: -1) + 1
            val input =
                CollaborationNotificationReplyInput(
                    id,
                    CollaborationNotificationReplyRevision(generation, revision),
                    revision,
                    candidate,
                )
            val next = active + CollaborationNotificationReplyCodec.reference(input)
            val document = codec.input(input)
            val written = publish(codec, raw, generation, revision, next, document)
            if (written) CollaborationNotificationReplyRetention.Retained(input)
            else CollaborationNotificationReplyRetention.Contended
        }
    }

    override suspend fun get(access: CollaborationRecoveryAccess, id: String) = safeStorageCall {
        require(validCollaborationNotificationReplyId(id))
        val raw =
            replicas.checkpoint(recoveryScope(access), NOTIFICATION_REPLY_PARTITION)
                ?: return@safeStorageCall null
        keys.use { key, index ->
            val codec = CollaborationNotificationReplyCodec(access, cipher, key, index)
            val reference = codec.checkpoint(raw).find { it.id == id }
            val input = reference?.let { load(codec, raw, it) }
            require(current(codec, raw))
            input
        }
    }

    override suspend fun page(
        access: CollaborationRecoveryAccess,
        after: Long?,
        limit: Int,
        pendingOnly: Boolean,
    ) = safeStorageCall {
        require(limit in 1..20 && (after == null || after in 0 until 9_007_199_254_740_991L))
        val raw =
            replicas.checkpoint(recoveryScope(access), NOTIFICATION_REPLY_PARTITION)
                ?: return@safeStorageCall CollaborationNotificationReplyPage(emptyList(), null)
        keys.use { key, index ->
            val codec = CollaborationNotificationReplyCodec(access, cipher, key, index)
            val remaining =
                codec
                    .checkpoint(raw)
                    .filter {
                        (after == null || it.sequence > after) &&
                            (!pendingOnly || it.review == null)
                    }
                    .sortedBy { it.sequence }
            val selected = remaining.take(limit)
            val inputs = selected.map { load(codec, raw, it) }
            require(current(codec, raw))
            CollaborationNotificationReplyPage(
                inputs.toList(),
                if (remaining.size > limit) selected.last().sequence else null,
            )
        }
    }

    override suspend fun hold(
        access: CollaborationRecoveryAccess,
        expected: CollaborationNotificationReplyInput,
        reason: CollaborationNotificationReplyReview,
    ) = safeStorageCall { change(access, expected, reason) }

    override suspend fun saveDraft(
        access: CollaborationRecoveryAccess,
        expected: CollaborationNotificationReplyInput,
        text: String,
    ) = safeStorageCall {
        require(
            expected.review != null &&
                expected.resolution == null &&
                validCollaborationReplyDraft(text)
        )
        edit(access, expected, expected.copy(draft = text))
    }

    override suspend fun confirm(
        access: CollaborationRecoveryAccess,
        expected: CollaborationNotificationReplyInput,
        resolution: CollaborationNotificationReplyResolution,
    ) = safeStorageCall {
        require(validCollaborationReplyResolution(expected.candidate, resolution))
        val previous = expected.resolution
        require(previous != null || expected.review != null)
        require(previous == null || previous.copy(parent = resolution.parent) == resolution)
        edit(access, expected, expected.copy(review = null, draft = null, resolution = resolution))
    }

    private suspend fun edit(
        access: CollaborationRecoveryAccess,
        expected: CollaborationNotificationReplyInput,
        changed: CollaborationNotificationReplyInput,
    ): Boolean {
        require(
            validCollaborationNotificationReplyInput(expected) &&
                validCollaborationNotificationReplyInput(changed)
        )
        require(
            expected.candidate.parent.audience == access.audience &&
                expected.candidate.parent.accountId == access.accountId
        )
        val raw =
            replicas.checkpoint(recoveryScope(access), NOTIFICATION_REPLY_PARTITION) ?: return false
        return keys.use { key, index ->
            val codec = CollaborationNotificationReplyCodec(access, cipher, key, index)
            val active = codec.checkpoint(raw)
            val reference = active.find { it.id == expected.id } ?: return@use false
            val found = load(codec, raw, reference)
            if (found != expected) return@use false
            if (found == changed) return@use current(codec, raw)
            val revision = raw.revision + 1
            val updated = changed.copy(version = changed.version.copy(revision = revision))
            val next =
                active.map {
                    if (it.id == found.id) CollaborationNotificationReplyCodec.reference(updated)
                    else it
                }
            publish(codec, raw, raw.handle.generation, revision, next, codec.input(updated))
        }
    }

    override suspend fun remove(
        access: CollaborationRecoveryAccess,
        expected: CollaborationNotificationReplyInput,
    ) = safeStorageCall { change(access, expected, null) }

    private suspend fun change(
        access: CollaborationRecoveryAccess,
        expected: CollaborationNotificationReplyInput,
        reason: CollaborationNotificationReplyReview?,
    ): Boolean {
        require(validCollaborationNotificationReplyInput(expected))
        require(
            expected.candidate.parent.audience == access.audience &&
                expected.candidate.parent.accountId == access.accountId
        )
        val raw =
            replicas.checkpoint(recoveryScope(access), NOTIFICATION_REPLY_PARTITION)
                ?: return reason == null
        return keys.use { key, index ->
            val codec = CollaborationNotificationReplyCodec(access, cipher, key, index)
            val active = codec.checkpoint(raw)
            val reference =
                active.find { it.id == expected.id }
                    ?: return@use reason == null && current(codec, raw)
            val found = load(codec, raw, reference)
            if (found != expected) return@use false
            if (reason != null && found.review == reason) return@use current(codec, raw)
            val revision = raw.revision + 1
            val next: List<NotificationReplyReference>
            val document: ReplicaDocument
            if (reason == null) {
                next = active.filterNot { it.id == found.id }
                document =
                    ReplicaDocument(
                        found.id,
                        revision,
                        CollaborationNotificationReplyCodec.sort(reference),
                        null,
                    )
            } else {
                val changed =
                    found.copy(version = found.version.copy(revision = revision), review = reason)
                next =
                    active.map {
                        if (it.id == found.id)
                            CollaborationNotificationReplyCodec.reference(changed)
                        else it
                    }
                document = codec.input(changed)
            }
            publish(codec, raw, raw.handle.generation, revision, next, document)
        }
    }

    private suspend fun load(
        codec: CollaborationNotificationReplyCodec,
        raw: ReplicaCheckpoint,
        reference: NotificationReplyReference,
    ): CollaborationNotificationReplyInput =
        codec.input(
            raw,
            reference,
            requireNotNull(
                replicas.document(codec.scope, NOTIFICATION_REPLY_PARTITION, reference.id)
            ),
        )

    private suspend fun current(
        codec: CollaborationNotificationReplyCodec,
        raw: ReplicaCheckpoint?,
    ) = replicas.checkpoint(codec.scope, NOTIFICATION_REPLY_PARTITION) == raw

    private suspend fun publish(
        codec: CollaborationNotificationReplyCodec,
        raw: ReplicaCheckpoint?,
        generation: String,
        revision: Long,
        active: List<NotificationReplyReference>,
        document: ReplicaDocument,
    ): Boolean {
        val payload = codec.checkpoint(generation, revision, active)
        return if (raw == null)
            replicas.replace(
                codec.scope,
                NOTIFICATION_REPLY_PARTITION,
                null,
                ReplicaSnapshot(generation, payload, listOf(document)),
            )
        else replicas.commit(raw.handle, raw.revision, payload, listOf(document), true)
    }
}
