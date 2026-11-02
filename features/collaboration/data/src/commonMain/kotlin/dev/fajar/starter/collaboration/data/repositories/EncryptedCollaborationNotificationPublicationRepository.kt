@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.history.CollaborationHistoryKeys
import dev.fajar.starter.collaboration.data.notifications.*
import dev.fajar.starter.collaboration.data.recovery.recoveryScope
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationNotificationPublicationRepository
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.storage.safeStorageCall
import kotlin.uuid.Uuid
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.koin.core.annotation.*

@Single
class EncryptedCollaborationNotificationPublicationRepository(
    private val replicas: ReplicaStore,
    private val cipher: ContentCipher,
    @Named("collaboration-history-content-key") private val credentials: AtomicCredentialStore,
) : CollaborationNotificationPublicationRepository {
    private val keys = CollaborationHistoryKeys(replicas, cipher, credentials)

    override suspend fun read(
        access: CollaborationRecoveryAccess,
        companyId: String,
        conversationId: String,
    ) = safeStorageCall {
        require(
            validCollaborationMessageId(companyId) && validCollaborationMessageId(conversationId)
        )
        val raw =
            replicas.checkpoint(recoveryScope(access), NOTIFICATION_PUBLICATION_PARTITION)
                ?: return@safeStorageCall null
        keys.use { key, index ->
            val codec = CollaborationNotificationPublicationCodec(access, cipher, key, index)
            val active = codec.checkpoint(raw)
            val value = load(codec, raw, active, codec.id(companyId, conversationId))
            require(current(codec, raw))
            value
        }
    }

    override suspend fun replace(
        access: CollaborationRecoveryAccess,
        expected: CollaborationNotificationPublication?,
        body: CollaborationNotificationPublicationBody,
    ) = safeStorageCall {
        require(validCollaborationNotificationPublicationBody(body))
        require(
            body.parent.audience == access.audience && body.parent.accountId == access.accountId
        )
        require(expected == null || validCollaborationNotificationPublication(expected))
        val raw = replicas.checkpoint(recoveryScope(access), NOTIFICATION_PUBLICATION_PARTITION)
        keys.use { key, index ->
            val codec = CollaborationNotificationPublicationCodec(access, cipher, key, index)
            val active = raw?.let { codec.checkpoint(it) } ?: emptyList()
            val id = codec.id(body.target.companyId, body.target.conversationId)
            require(expected == null || expected.id == id)
            val previous = raw?.let { load(codec, it, active, id) }
            if (previous != expected) return@use CollaborationNotificationPublicationWrite.Contended
            val remaining = active.filter { it.id != id }
            if (
                body.state.isActivePublication() &&
                    remaining.size >= COLLABORATION_NOTIFICATION_PUBLICATION_CAPACITY
            )
                return@use if (current(codec, raw))
                    CollaborationNotificationPublicationWrite.CapacityExceeded
                else CollaborationNotificationPublicationWrite.Contended
            val generation = raw?.handle?.generation ?: Uuid.random().toString()
            val revision = (raw?.revision ?: -1) + 1
            val value =
                CollaborationNotificationPublication(
                    id,
                    CollaborationNotificationPublicationRevision(generation, revision),
                    body,
                )
            val references =
                remaining +
                    if (body.state.isActivePublication())
                        listOf(CollaborationNotificationPublicationCodec.reference(value))
                    else emptyList()
            val document = codec.publication(value)
            val checkpoint = codec.checkpoint(generation, revision, references)
            currentCoroutineContext().ensureActive()
            val committed =
                if (raw == null)
                    replicas.replace(
                        codec.scope,
                        NOTIFICATION_PUBLICATION_PARTITION,
                        null,
                        ReplicaSnapshot(generation, checkpoint, listOf(document)),
                    )
                else replicas.commit(raw.handle, raw.revision, checkpoint, listOf(document), true)
            currentCoroutineContext().ensureActive()
            if (committed) CollaborationNotificationPublicationWrite.Stored(value)
            else CollaborationNotificationPublicationWrite.Contended
        }
    }

    override suspend fun page(
        access: CollaborationRecoveryAccess,
        after: String?,
        limit: Int,
        preparedOnly: Boolean,
    ) = safeStorageCall {
        require(
            limit in 1..4 && (after == null || validCollaborationNotificationPublicationId(after))
        )
        val raw =
            replicas.checkpoint(recoveryScope(access), NOTIFICATION_PUBLICATION_PARTITION)
                ?: return@safeStorageCall CollaborationNotificationPublicationPage(
                    emptyList(),
                    null,
                )
        keys.use { key, index ->
            val codec = CollaborationNotificationPublicationCodec(access, cipher, key, index)
            val active = codec.checkpoint(raw)
            val remaining =
                active
                    .filter {
                        (after == null || it.id > after) &&
                            (!preparedOnly ||
                                it.state == CollaborationNotificationPublicationState.Prepared.name)
                    }
                    .sortedBy { it.id }
            val selected = remaining.take(limit)
            val entries = selected.map { requireNotNull(load(codec, raw, active, it.id)) }
            require(current(codec, raw))
            CollaborationNotificationPublicationPage(
                entries.toList(),
                selected.lastOrNull()?.id?.takeIf { remaining.size > limit },
            )
        }
    }

    private suspend fun load(
        codec: CollaborationNotificationPublicationCodec,
        raw: ReplicaCheckpoint,
        active: List<PublicationReference>,
        id: String,
    ): CollaborationNotificationPublication? {
        val document = replicas.document(codec.scope, NOTIFICATION_PUBLICATION_PARTITION, id)
        if (document == null) {
            require(active.none { it.id == id })
            return null
        }
        require(document.id == id)
        return codec.publication(raw, active, document)
    }

    private suspend fun current(
        codec: CollaborationNotificationPublicationCodec,
        expected: ReplicaCheckpoint?,
    ) = replicas.checkpoint(codec.scope, NOTIFICATION_PUBLICATION_PARTITION) == expected
}
