@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.data.notifications.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationNotificationSettingsRepository
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.storage.safeStorageCall
import kotlin.uuid.Uuid
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.koin.core.annotation.*

@Single
class EncryptedCollaborationNotificationSettingsRepository(
    private val replicas: ReplicaStore,
    private val cipher: ContentCipher,
    @Named("collaboration-history-content-key") private val credentials: AtomicCredentialStore,
) : CollaborationNotificationSettingsRepository {
    private val keys = CollaborationHistoryKeys(replicas, cipher, credentials)

    override suspend fun read(access: CollaborationLocalAccess, conversationId: String) =
        safeStorageCall {
            val scope = historyScope(access)
            val partition = notificationSettingsPartition(access, conversationId)
            val raw =
                replicas.checkpoint(scope, partition)
                    ?: return@safeStorageCall CollaborationNotificationSettingsState()
            keys.use { key, _ ->
                val value =
                    CollaborationNotificationSettingsCodec(access, conversationId, cipher, key)
                        .open(raw)
                require(replicas.checkpoint(scope, partition) == raw)
                value
            }
        }

    override suspend fun update(
        access: CollaborationLocalAccess,
        conversationId: String,
        change: CollaborationNotificationSettingsChange,
    ) = safeStorageCall {
        require(
            validCollaborationMessageId(change.operationId) &&
                validCollaborationNotificationRevision(change.expected) &&
                validCollaborationNotificationSettings(change.settings)
        )
        val scope = historyScope(access)
        val partition = notificationSettingsPartition(access, conversationId)
        val raw = replicas.checkpoint(scope, partition)
        if (raw == null && change.expected != null) return@safeStorageCall false
        keys.use(create = raw == null) { key, _ ->
            val codec = CollaborationNotificationSettingsCodec(access, conversationId, cipher, key)
            val previous = raw?.let { codec.open(it) } ?: CollaborationNotificationSettingsState()
            // A lost reply may be retried, but an old action cannot overwrite a later change.
            if (previous.operationId == change.operationId) {
                require(previous.settings == change.settings)
                return@use replicas.checkpoint(scope, partition) == raw
            }
            if (previous.version != change.expected) return@use false
            val version =
                CollaborationNotificationSettingsRevision(
                    raw?.handle?.generation ?: Uuid.random().toString(),
                    (raw?.revision ?: -1) + 1,
                )
            val payload =
                codec.seal(
                    CollaborationNotificationSettingsState(
                        version,
                        change.operationId,
                        change.settings,
                    )
                )
            currentCoroutineContext().ensureActive()
            if (raw == null)
                replicas.replace(
                    scope,
                    partition,
                    null,
                    ReplicaSnapshot(version.generation, payload, emptyList()),
                )
            else replicas.commit(raw.handle, raw.revision, payload, emptyList(), true)
        }
    }
}
