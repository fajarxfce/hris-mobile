@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.data.inbox.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceInboxHistorySourceRepository
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.storage.safeStorageCall
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.koin.core.annotation.*

@Single
class EncryptedCollaborationSpaceInboxHistorySourceRepository(
    private val replicas: ReplicaStore,
    private val cipher: ContentCipher,
    @Named("collaboration-history-content-key") private val credentials: AtomicCredentialStore,
) : CollaborationSpaceInboxHistorySourceRepository {
    private val keys = CollaborationHistoryKeys(replicas, cipher, credentials)

    override suspend fun current(access: CollaborationSpaceLocalAccess, conversationId: String) =
        withContext(Dispatchers.Default) {
            safeStorageCall {
                val partition = spaceInboxHistorySourcePartition(access, conversationId)
                val raw =
                    replicas.checkpoint(spaceHistoryScope(access), partition)
                        ?: return@safeStorageCall null
                keys.use { content, index ->
                    CollaborationSpaceInboxHistorySourceCodec(
                            access,
                            conversationId,
                            cipher,
                            content,
                            index,
                        )
                        .checkpoint(raw)
                }
            }
        }

    override suspend fun mutate(
        access: CollaborationSpaceLocalAccess,
        mutation: CollaborationSpaceInboxHistorySourceMutation,
    ) =
        withContext(Dispatchers.Default) {
            safeStorageCall {
                val retained = mutation.retainedEventIds.toList()
                val unresolved = mutation.unresolvedEventIds.toList()
                val ids = retained + unresolved
                val captured =
                    mutation.copy(retainedEventIds = retained, unresolvedEventIds = unresolved)
                require(validCollaborationSpaceInboxHistorySourceMutation(access, captured))
                val expected = captured.expected
                val replacement = captured.replacement
                val scope = spaceHistoryScope(access)
                val partition = spaceInboxHistorySourcePartition(access, replacement.conversationId)
                val raw = replicas.checkpoint(scope, partition)
                if ((raw == null) != (expected == null)) return@safeStorageCall null
                require(raw == null || raw.revision < COLLABORATION_SPACE_INBOX_MAX_ORDER)
                val next =
                    CollaborationSpaceInboxHistorySourceCheckpoint(
                        CollaborationSpaceInboxHistoryProgressVersion(
                            raw?.handle?.generation ?: Uuid.random().toString(),
                            raw?.revision?.plus(1) ?: 0,
                        ),
                        replacement,
                    )
                require(validCollaborationSpaceInboxHistorySourceCheckpoint(access, next))
                keys.use(create = raw == null) { content, index ->
                    val codec =
                        CollaborationSpaceInboxHistorySourceCodec(
                            access,
                            replacement.conversationId,
                            cipher,
                            content,
                            index,
                        )
                    val actual = raw?.let { codec.checkpoint(it) }
                    if (actual != expected) return@use null
                    val indexPartition = spaceInboxPartition(access)
                    val indexRaw = replicas.checkpoint(scope, indexPartition)
                    val indexVersion =
                        indexRaw?.let {
                            CollaborationSpaceInboxCodec(access, cipher, content, index).version(it)
                        }
                    if (
                        !collaborationSpaceInboxHistoryDestinationCovers(
                            mutation.destination,
                            indexVersion,
                        )
                    )
                        return@use null
                    val existing =
                        if (ids.isEmpty()) emptyList()
                        else {
                            val read =
                                replicas.workingDocuments(
                                    scope,
                                    partition,
                                    ids.map { codec.id(it) },
                                )
                            if (read.checkpoint != raw) return@use null
                            read.documents
                        }
                    val write = codec.mutation(raw, actual, next, existing, retained, unresolved)
                    currentCoroutineContext().ensureActive()
                    if (
                        replicas.mutate(
                            scope,
                            listOf(ReplicaMutation.Check(indexPartition, indexRaw), write),
                        )
                    )
                        next
                    else null
                }
            }
        }

    override suspend fun sources(
        access: CollaborationSpaceLocalAccess,
        after: String?,
        limit: Int,
    ) =
        withContext(Dispatchers.Default) {
            safeStorageCall {
                require(limit in 1..16)
                val prefix = spaceInboxHistorySourcePrefix(access)
                require(
                    after == null ||
                        after.startsWith(prefix) &&
                            validCollaborationMessageId(after.removePrefix(prefix))
                )
                val rows =
                    replicas
                        .observePublishedCheckpoints(
                            spaceHistoryScope(access),
                            prefix,
                            after,
                            limit + 1,
                        )
                        .first()
                require(rows.size <= limit + 1)
                require(
                    rows.zipWithNext().all { (a, b) -> a.handle.partition < b.handle.partition }
                )
                if (rows.isEmpty())
                    return@safeStorageCall CollaborationSpaceInboxHistorySourcePage(
                        emptyList(),
                        null,
                    )
                keys.use { content, index ->
                    val sources =
                        rows.map { raw ->
                            require(
                                raw.handle.partition.startsWith(prefix) &&
                                    (after == null || raw.handle.partition > after)
                            )
                            val id = raw.handle.partition.removePrefix(prefix)
                            CollaborationSpaceInboxHistorySourceCodec(
                                    access,
                                    id,
                                    cipher,
                                    content,
                                    index,
                                )
                                .checkpoint(raw)
                        }
                    CollaborationSpaceInboxHistorySourcePage(
                        sources.take(limit),
                        if (sources.size > limit) rows[limit - 1].handle.partition else null,
                    )
                }
            }
        }

    override suspend fun pending(
        access: CollaborationSpaceLocalAccess,
        conversationId: String,
        expected: CollaborationSpaceInboxHistoryProgressVersion,
        limit: Int,
    ) =
        withContext(Dispatchers.Default) {
            safeStorageCall {
                require(limit in 1..16 && validCollaborationMessageId(expected.generation))
                require(expected.revision in 0..COLLABORATION_SPACE_INBOX_MAX_ORDER)
                val scope = spaceHistoryScope(access)
                val partition = spaceInboxHistorySourcePartition(access, conversationId)
                val raw = replicas.checkpoint(scope, partition) ?: return@safeStorageCall null
                keys.use { content, index ->
                    val codec =
                        CollaborationSpaceInboxHistorySourceCodec(
                            access,
                            conversationId,
                            cipher,
                            content,
                            index,
                        )
                    val current = codec.checkpoint(raw)
                    if (current.version != expected) return@use null
                    val window =
                        current.progress.pendingWindow
                            ?: CollaborationSpaceInboxHistoryPendingWindow(
                                expected.generation,
                                expected.revision,
                                null,
                                false,
                            )
                    val after = window.after
                    codec.pendingAfter(after, window.throughRevision)
                    if (window.complete)
                        return@use CollaborationSpaceInboxHistoryPendingPage(
                            expected,
                            window,
                            emptyList(),
                            null,
                        )
                    val page =
                        replicas
                            .observeQueries(
                                scope,
                                partition,
                                listOf(
                                    ReplicaQuery(
                                        SPACE_INBOX_HISTORY_REFERENCES,
                                        window.after,
                                        limit + 1,
                                    )
                                ),
                            )
                            .first()
                    if (page.checkpoint != raw) return@use null
                    require(page.pages.size == 1)
                    val rows = page.pages.single()
                    require(rows.size <= limit + 1)
                    require(rows.zipWithNext().all { (a, b) -> a.sortKey < b.sortKey })
                    require(rows.all { after == null || it.sortKey > after })
                    val references = rows.map { codec.reference(current, it) }
                    val interval =
                        references.takeWhile { it.insertedAtRevision <= window.throughRevision }
                    CollaborationSpaceInboxHistoryPendingPage(
                        expected,
                        window,
                        interval.take(limit),
                        if (interval.size > limit) rows[limit - 1].sortKey else null,
                    )
                }
            }
        }
}
