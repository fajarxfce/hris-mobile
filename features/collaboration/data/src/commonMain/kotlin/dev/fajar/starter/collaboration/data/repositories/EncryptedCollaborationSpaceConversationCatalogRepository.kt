package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.history.CollaborationHistoryKeys
import dev.fajar.starter.collaboration.data.identity.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.entities.CollaborationSpaceConversationCatalogRead as CatalogRead
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceConversationCatalogRepository
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.storage.safeStorageCall
import dev.fajar.starter.storage.safeStorageFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import org.koin.core.annotation.*

/** Enumerates the existing canonical indexes without rewriting metadata or copying chat content. */
@Single
class EncryptedCollaborationSpaceConversationCatalogRepository(
    private val replicas: ReplicaStore,
    private val cipher: ContentCipher,
    @Named("collaboration-history-content-key") private val credentials: AtomicCredentialStore,
) : CollaborationSpaceConversationCatalogRepository {
    private val keys = CollaborationHistoryKeys(replicas, cipher, credentials)

    override fun observeVersion(access: CollaborationSpaceLocalAccess) =
        safeStorageFlow(
                flow {
                    emitAll(
                        replicas.observePublishedCheckpoint(
                            spaceConversationMetadataScope(access),
                            spaceConversationMetadataPartition(access),
                        )
                    )
                },
                mapValue = { checkpoint ->
                    checkpoint?.let {
                        keys.use { content, index ->
                            CollaborationSpaceConversationMetadataCodec(
                                    access,
                                    cipher,
                                    content,
                                    index,
                                )
                                .validate(it)
                            CollaborationSpaceConversationCatalogVersion(
                                it.handle.generation,
                                it.revision,
                            )
                        }
                    }
                },
            )
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    override suspend fun read(
        access: CollaborationSpaceLocalAccess,
        after: String?,
        rawLimit: Int,
    ) =
        withContext(Dispatchers.Default) {
            safeStorageCall {
                require(rawLimit in 1..50 && (after == null || after.length in 1..4096))
                val scope = spaceConversationMetadataScope(access)
                val partition = spaceConversationMetadataPartition(access)
                val initial = replicas.observePublishedCheckpoint(scope, partition).first()
                if (initial == null)
                    return@safeStorageCall if (after == null)
                        CatalogRead.Page(null, emptyList(), null, changed = false)
                    else CatalogRead.Restart
                keys.use { content, index ->
                    val codec =
                        CollaborationSpaceConversationMetadataCodec(access, cipher, content, index)
                    codec.validate(initial)
                    val position = after?.let { codec.catalogPosition(it) }
                    if (position != null && position.generation != initial.handle.generation)
                        return@use CatalogRead.Restart
                    val version = position?.revision ?: initial.revision
                    require(version <= initial.revision)
                    val page =
                        replicas.observe(scope, partition, position?.after, rawLimit + 1).first()
                    val checkpoint = page.checkpoint
                    if (checkpoint?.handle != initial.handle) return@use CatalogRead.Restart
                    codec.validate(checkpoint)
                    require(
                        checkpoint.revision >= initial.revision &&
                            page.documents.size <= rawLimit + 1
                    )
                    require(page.documents.zipWithNext().all { (a, b) -> a.sortKey < b.sortKey })
                    require(page.documents.all { position == null || it.sortKey > position.after })
                    val selected = page.documents.take(rawLimit)
                    val items = mutableListOf<CollaborationSpaceConversationMetadata>()
                    for (raw in selected) {
                        val value = codec.decode(checkpoint, raw)
                        val ids = codec.ids(value)
                        if (raw.version > version || raw.id != ids.first()) continue
                        val pair =
                            replicas
                                .observeQueries(
                                    scope,
                                    partition,
                                    ids.map { ReplicaQuery(tag = it, limit = 2) },
                                )
                                .first()
                        val current = pair.checkpoint
                        if (current?.handle != initial.handle) return@use CatalogRead.Restart
                        codec.validate(current)
                        require(current.revision >= checkpoint.revision)
                        require(pair.pages.size == 2 && pair.pages.all { it.size == 1 })
                        val documents = pair.pages.flatten()
                        require(documents.map { it.id } == ids && raw in documents)
                        require(codec.pair(current, documents) == value)
                        items += value
                    }
                    val latest = replicas.observePublishedCheckpoint(scope, partition).first()
                    if (latest?.handle != initial.handle) return@use CatalogRead.Restart
                    codec.validate(latest)
                    require(latest.revision >= checkpoint.revision)
                    val next =
                        if (page.documents.size > rawLimit)
                            codec.catalogCursor(
                                CollaborationSpaceConversationCatalogPosition(
                                    initial.handle.generation,
                                    version,
                                    selected.last().sortKey,
                                )
                            )
                        else null
                    CatalogRead.Page(
                        CollaborationSpaceConversationCatalogVersion(
                            initial.handle.generation,
                            version,
                        ),
                        items.toList(),
                        next,
                        changed = latest.revision != version,
                    )
                }
            }
        }
}
