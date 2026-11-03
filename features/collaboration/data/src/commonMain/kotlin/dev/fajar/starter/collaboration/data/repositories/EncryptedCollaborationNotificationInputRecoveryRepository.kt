@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.history.CollaborationHistoryKeys
import dev.fajar.starter.collaboration.data.notifications.*
import dev.fajar.starter.collaboration.data.recovery.recoveryScope
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationNotificationInputRecoveryRepository
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.storage.safeStorageCall
import kotlin.uuid.Uuid
import org.koin.core.annotation.*

@Single
class EncryptedCollaborationNotificationInputRecoveryRepository(
    private val replicas: ReplicaStore,
    private val cipher: ContentCipher,
    @Named("collaboration-history-content-key") credentials: AtomicCredentialStore,
) : CollaborationNotificationInputRecoveryRepository {
    private val keys = CollaborationHistoryKeys(replicas, cipher, credentials)

    override suspend fun current(access: CollaborationRecoveryAccess) = safeStorageCall {
        val raw =
            replicas.checkpoint(recoveryScope(access), NOTIFICATION_INPUT_RECOVERY_PARTITION)
                ?: return@safeStorageCall null
        keys.use(create = false) { content, _ ->
            CollaborationNotificationInputRecoveryCodec(access, cipher, content).decode(raw)
        }
    }

    override suspend fun replace(
        access: CollaborationRecoveryAccess,
        expected: CollaborationNotificationInputRecoveryCursor?,
        state: CollaborationNotificationInputRecoveryState,
    ) = safeStorageCall {
        require(expected == null || validCollaborationNotificationInputRecoveryCursor(expected))
        val retainedState = state.copy(references = state.references.toList())
        require(validCollaborationNotificationInputRecoveryState(retainedState))
        val scope = recoveryScope(access)
        val raw = replicas.checkpoint(scope, NOTIFICATION_INPUT_RECOVERY_PARTITION)
        if (raw == null && expected != null) return@safeStorageCall null
        keys.use(create = false) { content, _ ->
            val codec = CollaborationNotificationInputRecoveryCodec(access, cipher, content)
            if (raw?.let { codec.decode(it) } != expected) return@use null
            val next =
                CollaborationNotificationInputRecoveryCursor(
                    Uuid.random().toString(),
                    retainedState,
                )
            if (
                replicas.replace(
                    scope,
                    NOTIFICATION_INPUT_RECOVERY_PARTITION,
                    raw,
                    ReplicaSnapshot(next.generation, codec.encode(next), emptyList()),
                )
            )
                next
            else null
        }
    }
}
