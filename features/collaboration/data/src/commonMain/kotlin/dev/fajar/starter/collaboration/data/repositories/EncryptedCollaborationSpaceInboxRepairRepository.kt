package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.data.inbox.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.validCollaborationMessageId
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceInboxRepairRepository
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.koin.core.annotation.*

@Single
class EncryptedCollaborationSpaceInboxRepairRepository(
    private val replicas: ReplicaStore,
    private val cipher: ContentCipher,
    @Named("collaboration-history-content-key") private val credentials: AtomicCredentialStore,
) : CollaborationSpaceInboxRepairRepository {
    private val keys = CollaborationHistoryKeys(replicas, cipher, credentials)

    override suspend fun get(
        access: CollaborationSpaceLocalAccess,
        conversationId: String,
        messageId: String,
    ): AppResult<CollaborationSpaceInboxRepairReference?> =
        withContext(Dispatchers.Default) {
            safeStorageCall {
                require(
                    validCollaborationMessageId(conversationId) &&
                        validCollaborationMessageId(messageId)
                )
                val scope = spaceHistoryScope(access)
                val partition = spaceInboxPartition(access)
                if (replicas.observePublishedCheckpoint(scope, partition).first() == null)
                    return@safeStorageCall null
                keys.use { content, index ->
                    val codec = CollaborationSpaceInboxCodec(access, cipher, content, index)
                    val id =
                        codec.id(
                            "repair",
                            conversationId,
                            CollaborationSpaceInboxSource.GroupOriginal(messageId),
                        )
                    val page = replicas.workingDocuments(scope, partition, listOf(id))
                    val raw = page.checkpoint
                    if (raw == null) {
                        require(page.documents.isEmpty())
                        return@use null
                    }
                    codec.version(raw)
                    require(page.documents.size <= 1 && page.documents.all { it.id == id })
                    page.documents.singleOrNull()?.let { document ->
                        val decoded = codec.decode(raw, document)
                        require(decoded.conversationId == conversationId)
                        val repair = requireNotNull(decoded.repair)
                        require(repair.id == messageId)
                        repair.reference(access)
                    }
                }
            }
        }

    override suspend fun page(
        access: CollaborationSpaceLocalAccess,
        after: String?,
        limit: Int,
    ): AppResult<CollaborationSpaceInboxRepairPage> =
        withContext(Dispatchers.Default) {
            safeStorageCall {
                require(limit in 1..32 && (after == null || after.length in 1..4096))
                val scope = spaceHistoryScope(access)
                val partition = spaceInboxPartition(access)
                if (replicas.observePublishedCheckpoint(scope, partition).first() == null)
                    return@safeStorageCall if (after == null)
                        CollaborationSpaceInboxRepairPage.Items(null, emptyList(), null)
                    else CollaborationSpaceInboxRepairPage.Restart
                keys.use { content, index ->
                    val codec = CollaborationSpaceInboxCodec(access, cipher, content, index)
                    val cursor = after?.let { codec.repairCursor(it) }
                    val page =
                        replicas
                            .observeQueries(
                                scope,
                                partition,
                                listOf(ReplicaQuery(SPACE_INBOX_REPAIRS, cursor?.after, limit + 1)),
                            )
                            .first()
                    require(page.pages.size == 1)
                    val raw = page.checkpoint
                    if (raw == null) {
                        require(page.pages.single().isEmpty())
                        return@use if (cursor == null)
                            CollaborationSpaceInboxRepairPage.Items(null, emptyList(), null)
                        else CollaborationSpaceInboxRepairPage.Restart
                    }
                    val version = codec.version(raw)
                    if (
                        cursor != null &&
                            (cursor.generation != version.generation ||
                                cursor.highRevision > version.revision)
                    )
                        return@use CollaborationSpaceInboxRepairPage.Restart
                    val highRevision = cursor?.highRevision ?: version.revision
                    val documents = page.pages.single()
                    require(
                        documents.size <= limit + 1 &&
                            documents.zipWithNext().all { (a, b) -> a.sortKey < b.sortKey }
                    )
                    require(documents.all { cursor == null || it.sortKey > cursor.after })
                    val decoded =
                        documents.map { document ->
                            val value = requireNotNull(codec.decode(raw, document).repair)
                            document to requireNotNull(value.reference(access))
                        }
                    val captured =
                        decoded.takeWhile { it.second.insertedAtRevision <= highRevision }
                    require(
                        decoded.drop(captured.size).all {
                            it.second.insertedAtRevision > highRevision
                        }
                    )
                    val selected = captured.take(limit)
                    CollaborationSpaceInboxRepairPage.Items(
                        version,
                        selected.map { it.second }.toList(),
                        if (captured.size > limit)
                            codec.repairCursor(
                                version.generation,
                                highRevision,
                                selected.last().first.sortKey,
                            )
                        else null,
                    )
                }
            }
        }
}
