@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.history.CollaborationHistoryKeys
import dev.fajar.starter.collaboration.data.recovery.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationRecoveryProgressRepository
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.storage.safeStorageCall
import kotlin.uuid.Uuid
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.koin.core.annotation.*

@Single
class EncryptedCollaborationRecoveryProgressRepository(
    private val replicas: ReplicaStore,
    private val cipher: ContentCipher,
    @Named("collaboration-history-content-key") private val credentials: AtomicCredentialStore,
) : CollaborationRecoveryProgressRepository {
    private val keys = CollaborationHistoryKeys(replicas, cipher, credentials)

    override suspend fun current(access: CollaborationRecoveryAccess) = safeStorageCall {
        currentCoroutineContext().ensureActive()
        replicas.checkpoint(recoveryScope(access), COMPANY_RECOVERY_PARTITION)?.let { raw ->
            keys.use { content, _ ->
                CollaborationRecoveryProgressCodec(access, cipher, content).decode(raw)
            }
        }
    }

    override suspend fun replace(
        access: CollaborationRecoveryAccess,
        expected: CollaborationRecoveryProgress?,
        sweep: CollaborationRecoverySweep?,
    ) = safeStorageCall {
        currentCoroutineContext().ensureActive()
        require(expected == null || validCollaborationRecoveryProgress(expected))
        require(sweep == null || validCollaborationRecoverySweep(sweep))
        val scope = recoveryScope(access)
        val raw = replicas.checkpoint(scope, COMPANY_RECOVERY_PARTITION)
        if (raw == null && expected != null) return@safeStorageCall null
        keys.use(create = raw == null && expected == null) { content, _ ->
            val codec = CollaborationRecoveryProgressCodec(access, cipher, content)
            if (raw?.let { codec.decode(it) } != expected) return@use null
            val value = CollaborationRecoveryProgress(Uuid.random().toString(), sweep)
            currentCoroutineContext().ensureActive()
            if (
                replicas.replace(
                    scope,
                    COMPANY_RECOVERY_PARTITION,
                    raw,
                    ReplicaSnapshot(value.generation, codec.encode(value), emptyList()),
                )
            )
                value
            else null
        }
    }
}
