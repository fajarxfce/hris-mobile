@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.data.inbox.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceInboxHistoryProgressRepository
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.storage.*
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.koin.core.annotation.*

@Single
class EncryptedCollaborationSpaceInboxHistoryProgressRepository(
    private val replicas: ReplicaStore,
    private val cipher: ContentCipher,
    @Named("collaboration-history-content-key") private val credentials: AtomicCredentialStore,
) : CollaborationSpaceInboxHistoryProgressRepository {
    private val keys = CollaborationHistoryKeys(replicas, cipher, credentials)

    override suspend fun current(access: CollaborationSpaceLocalAccess) =
        withContext(Dispatchers.Default) {
            safeStorageCall {
                val raw =
                    replicas.checkpoint(
                        spaceHistoryScope(access),
                        spaceInboxHistoryProgressPartition(access),
                    ) ?: return@safeStorageCall null
                keys.use { content, _ ->
                    CollaborationSpaceInboxHistoryProgressCodec(access, cipher, content).decode(raw)
                }
            }
        }

    override fun observeDestination(access: CollaborationSpaceLocalAccess) =
        safeStorageFlow(
                flow {
                    emitAll(
                        replicas.observePublishedCheckpoint(
                            spaceHistoryScope(access),
                            spaceInboxPartition(access),
                        )
                    )
                },
                mapValue = { raw ->
                    raw?.let {
                        keys.use { content, index ->
                            CollaborationSpaceInboxCodec(access, cipher, content, index).version(it)
                        }
                    }
                },
            )
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    override suspend fun mutate(
        access: CollaborationSpaceLocalAccess,
        mutation: CollaborationSpaceInboxHistoryMutation,
    ) = safeStorageCall {
        // DTO construction detaches every queue before dispatcher/key/storage suspension.
        val expected =
            mutation.expected?.let {
                it.copy(progress = InboxHistoryProgressDto(it.progress).domain())
            }
        val replacement = InboxHistoryProgressDto(mutation.replacement).domain()
        require(
            expected == null ||
                validCollaborationMessageId(expected.version.generation) &&
                    expected.version.revision in 0..COLLABORATION_SPACE_INBOX_MAX_ORDER
        )
        val guard = mutation.guard
        when (guard) {
            is CollaborationSpaceInboxHistoryGuard.Source ->
                require(
                    validCollaborationSpaceInboxHistorySourceCheckpoint(access, guard.checkpoint)
                )
            is CollaborationSpaceInboxHistoryGuard.Destination ->
                require(
                    collaborationSpaceInboxHistoryDestinationCovers(guard.version, guard.version)
                )
        }
        withContext(Dispatchers.Default) {
            val scope = spaceHistoryScope(access)
            val partition = spaceInboxHistoryProgressPartition(access)
            val raw = replicas.checkpoint(scope, partition)
            if ((raw == null) != (expected == null)) return@withContext null
            keys.use(create = raw == null) { content, index ->
                val codec = CollaborationSpaceInboxHistoryProgressCodec(access, cipher, content)
                val actual = raw?.let { codec.decode(it) }
                if (actual != expected) return@use null
                val checked = readGuard(access, guard, content, index) ?: return@use null
                if (actual?.progress == replacement) return@use actual
                require(raw == null || raw.revision < COLLABORATION_SPACE_INBOX_MAX_ORDER)
                val next =
                    CollaborationSpaceInboxHistoryCheckpoint(
                        CollaborationSpaceInboxHistoryProgressVersion(
                            raw?.handle?.generation ?: Uuid.random().toString(),
                            raw?.revision?.plus(1) ?: 0L,
                        ),
                        replacement,
                    )
                val payload = codec.encode(next)
                val write =
                    if (raw == null)
                        ReplicaMutation.Replace(
                            partition,
                            null,
                            ReplicaSnapshot(next.version.generation, payload, emptyList()),
                        )
                    else
                        ReplicaMutation.Commit(raw.handle, raw.revision, payload, emptyList(), true)
                currentCoroutineContext().ensureActive()
                if (replicas.mutate(scope, listOf(checked, write))) next else null
            }
        }
    }

    /**
     * Decode the exact raw checkpoint that the joint write will check, never another repository.
     */
    private suspend fun readGuard(
        access: CollaborationSpaceLocalAccess,
        guard: CollaborationSpaceInboxHistoryGuard,
        content: ByteArray,
        index: ByteArray,
    ): ReplicaMutation.Check? {
        val scope = spaceHistoryScope(access)
        return when (guard) {
            is CollaborationSpaceInboxHistoryGuard.Destination -> {
                val partition = spaceInboxPartition(access)
                val raw = replicas.checkpoint(scope, partition)
                val actual =
                    raw?.let {
                        CollaborationSpaceInboxCodec(access, cipher, content, index).version(it)
                    }
                if (!collaborationSpaceInboxHistoryDestinationCovers(guard.version, actual)) null
                else ReplicaMutation.Check(partition, raw)
            }
            is CollaborationSpaceInboxHistoryGuard.Source -> {
                val id = guard.checkpoint.progress.conversationId
                val partition = spaceInboxHistorySourcePartition(access, id)
                val raw = replicas.checkpoint(scope, partition) ?: return null
                val actual =
                    CollaborationSpaceInboxHistorySourceCodec(access, id, cipher, content, index)
                        .checkpoint(raw)
                if (actual != guard.checkpoint) null else ReplicaMutation.Check(partition, raw)
            }
        }
    }
}
