package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.data.inbox.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceInboxRepairProgressRepository
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.storage.safeStorageCall
import kotlinx.coroutines.*
import org.koin.core.annotation.*

@Single
class EncryptedCollaborationSpaceInboxRepairProgressRepository(
    private val replicas: ReplicaStore,
    private val cipher: ContentCipher,
    @Named("collaboration-history-content-key") private val credentials: AtomicCredentialStore,
) : CollaborationSpaceInboxRepairProgressRepository {
    private val keys = CollaborationHistoryKeys(replicas, cipher, credentials)

    override suspend fun current(access: CollaborationSpaceLocalAccess) =
        withContext(Dispatchers.Default) {
            safeStorageCall {
                replicas
                    .checkpoint(
                        spaceHistoryScope(access),
                        spaceInboxRepairProgressPartition(access),
                    )
                    ?.let { raw ->
                        keys.use { content, _ ->
                            CollaborationSpaceInboxRepairProgressCodec(access, cipher, content)
                                .decode(raw)
                        }
                    }
            }
        }

    override suspend fun replace(
        access: CollaborationSpaceLocalAccess,
        expected: CollaborationSpaceInboxRepairProgress?,
        replacement: CollaborationSpaceInboxRepairProgress,
    ) =
        withContext(Dispatchers.Default) {
            safeStorageCall {
                require(expected == null || validCollaborationSpaceInboxRepairProgress(expected))
                require(validCollaborationSpaceInboxRepairProgress(replacement))
                require(
                    expected == null ||
                        expected == replacement ||
                        expected.generation != replacement.generation
                )
                val scope = spaceHistoryScope(access)
                val partition = spaceInboxRepairProgressPartition(access)
                val raw = replicas.checkpoint(scope, partition)
                if (raw == null && expected != null) return@safeStorageCall null
                keys.use(create = raw == null && expected == null) { content, _ ->
                    val codec = CollaborationSpaceInboxRepairProgressCodec(access, cipher, content)
                    val actual = raw?.let { codec.decode(it) }
                    if (actual != expected) return@use null
                    if (actual == replacement) return@use actual
                    val snapshot =
                        ReplicaSnapshot(
                            replacement.generation,
                            codec.encode(replacement),
                            emptyList(),
                        )
                    currentCoroutineContext().ensureActive()
                    if (replicas.replace(scope, partition, raw, snapshot)) replacement else null
                }
            }
        }
}
