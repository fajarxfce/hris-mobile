@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.history.CollaborationHistoryKeys
import dev.fajar.starter.collaboration.data.notifications.*
import dev.fajar.starter.collaboration.data.recovery.recoveryScope
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationNotificationReplyRecoveryCursorRepository
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.storage.safeStorageCall
import kotlin.uuid.Uuid
import org.koin.core.annotation.*

@Single
class EncryptedCollaborationNotificationReplyRecoveryCursorRepository(
    private val replicas: ReplicaStore,
    private val cipher: ContentCipher,
    @Named("collaboration-history-content-key") credentials: AtomicCredentialStore,
) : CollaborationNotificationReplyRecoveryCursorRepository {
    private val keys = CollaborationHistoryKeys(replicas, cipher, credentials)

    override suspend fun current(access: CollaborationRecoveryAccess) = safeStorageCall {
        val raw =
            replicas.checkpoint(recoveryScope(access), NOTIFICATION_REPLY_RECOVERY_PARTITION)
                ?: return@safeStorageCall null
        keys.use(create = false) { content, _ ->
            CollaborationNotificationReplyRecoveryCursorCodec(access, cipher, content).decode(raw)
        }
    }

    override suspend fun advance(
        access: CollaborationRecoveryAccess,
        expected: CollaborationNotificationReplyRecoveryCursor?,
        afterSequence: Long,
    ) = safeStorageCall {
        require(validCollaborationNotificationReplyRecoverySequence(afterSequence))
        require(expected == null || validCollaborationNotificationReplyRecoveryCursor(expected))
        val scope = recoveryScope(access)
        val raw = replicas.checkpoint(scope, NOTIFICATION_REPLY_RECOVERY_PARTITION)
        if (raw == null && expected != null) return@safeStorageCall null
        keys.use(create = false) { content, _ ->
            val codec = CollaborationNotificationReplyRecoveryCursorCodec(access, cipher, content)
            val current = raw?.let { codec.decode(it) }
            if (current != expected) return@use null
            val next =
                CollaborationNotificationReplyRecoveryCursor(
                    Uuid.random().toString(),
                    afterSequence,
                )
            if (
                replicas.replace(
                    scope,
                    NOTIFICATION_REPLY_RECOVERY_PARTITION,
                    raw,
                    ReplicaSnapshot(next.generation, codec.encode(next), emptyList()),
                )
            )
                next
            else null
        }
    }
}
