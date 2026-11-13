package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.data.inbox.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceInboxRepository
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.koin.core.annotation.*

@Single
class EncryptedCollaborationSpaceInboxRepository(
    private val replicas: ReplicaStore,
    private val cipher: ContentCipher,
    @Named("collaboration-history-content-key") private val credentials: AtomicCredentialStore,
) : CollaborationSpaceInboxRepository {
    private val keys = CollaborationHistoryKeys(replicas, cipher, credentials)

    override fun observePage(
        access: CollaborationSpaceLocalAccess,
        after: String?,
        limit: Int,
    ): Flow<AppResult<CollaborationSpaceInboxPage>> =
        safeStorageFlow(
                flow {
                    require(limit in 1..50 && (after == null || after.length in 1..4096))
                    val position =
                        after?.let {
                            keys.use { content, index ->
                                CollaborationSpaceInboxCodec(access, cipher, content, index)
                                    .cursor(it)
                            }
                        }
                    emitAll(
                        replicas
                            .observeQueries(
                                spaceHistoryScope(access),
                                spaceInboxPartition(access),
                                listOf(
                                    ReplicaQuery(SPACE_INBOX_ENTRIES, position?.after, limit + 1)
                                ),
                            )
                            .map { it to position }
                            .distinctUntilChanged()
                    )
                },
                mapValue = { (page, position) ->
                    val raw = page.checkpoint
                    require(page.pages.size == 1)
                    if (raw == null) {
                        require(page.pages.single().isEmpty())
                        if (position == null)
                            CollaborationSpaceInboxPage.Items(null, emptyList(), null)
                        else CollaborationSpaceInboxPage.Restart
                    } else
                        keys.use { content, index ->
                            val codec = CollaborationSpaceInboxCodec(access, cipher, content, index)
                            val version = codec.version(raw)
                            if (
                                position != null &&
                                    (position.generation != version.generation ||
                                        position.revision != version.revision)
                            )
                                CollaborationSpaceInboxPage.Restart
                            else {
                                val documents = page.pages.single()
                                require(
                                    documents.size <= limit + 1 &&
                                        documents.zipWithNext().all { (a, b) ->
                                            a.sortKey < b.sortKey
                                        }
                                )
                                require(
                                    documents.all {
                                        position == null || it.sortKey > position.after
                                    }
                                )
                                val selected = documents.take(limit)
                                val entries =
                                    selected.map { document ->
                                        val value = codec.decode(raw, document)
                                        requireNotNull(value.entry).domain(value.conversationId)
                                    }
                                require(
                                    entries.map { it.conversationId }.distinct().size ==
                                        entries.size
                                )
                                CollaborationSpaceInboxPage.Items(
                                    version,
                                    entries.toList(),
                                    if (documents.size > limit)
                                        codec.cursor(version, selected.last().sortKey)
                                    else null,
                                )
                            }
                        }
                },
            )
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    override suspend fun conversation(
        access: CollaborationSpaceLocalAccess,
        conversationId: String,
        candidateLimit: Int,
    ): AppResult<CollaborationSpaceInboxConversation> =
        withContext(Dispatchers.Default) {
            safeStorageCall {
                require(validCollaborationMessageId(conversationId) && candidateLimit in 1..33)
                val scope = spaceHistoryScope(access)
                val partition = spaceInboxPartition(access)
                val initial = replicas.observePublishedCheckpoint(scope, partition).first()
                if (initial == null)
                    return@safeStorageCall CollaborationSpaceInboxConversation(
                        null,
                        null,
                        null,
                        emptyList(),
                    )
                keys.use { content, index ->
                    val codec = CollaborationSpaceInboxCodec(access, cipher, content, index)
                    codec.version(initial)
                    val entryId = codec.id("entry", conversationId)
                    val draftId = codec.id("draft", conversationId)
                    val candidatesTag = codec.id("candidates", conversationId)
                    val page =
                        replicas
                            .observeQueries(
                                scope,
                                partition,
                                listOf(
                                    ReplicaQuery(entryId, limit = 2),
                                    ReplicaQuery(draftId, limit = 2),
                                    ReplicaQuery(candidatesTag, limit = candidateLimit),
                                ),
                            )
                            .first()
                    val raw = page.checkpoint
                    require(page.pages.size == 3)
                    if (raw == null) {
                        require(page.pages.all { it.isEmpty() })
                        return@use CollaborationSpaceInboxConversation(
                            null,
                            null,
                            null,
                            emptyList(),
                        )
                    }
                    val version = codec.version(raw)
                    require(
                        page.pages[0].size <= 1 &&
                            page.pages[1].size <= 1 &&
                            page.pages[2].size <= candidateLimit
                    )
                    val decoded =
                        page.pages.map { documents ->
                            documents.map {
                                codec.decode(raw, it).also { value ->
                                    require(value.conversationId == conversationId)
                                }
                            }
                        }
                    val entry =
                        decoded[0].singleOrNull()?.let {
                            requireNotNull(it.entry).domain(conversationId)
                        }
                    val draft = decoded[1].singleOrNull()?.let { requireNotNull(it.draft).domain() }
                    val candidates =
                        decoded[2].map {
                            requireNotNull(it.activity).domain().also { activity ->
                                require(activity.preview != null)
                            }
                        }
                    require(page.pages[2].zipWithNext().all { (a, b) -> a.sortKey < b.sortKey })
                    CollaborationSpaceInboxConversation(version, entry, draft, candidates.toList())
                }
            }
        }

    override suspend fun activities(
        access: CollaborationSpaceLocalAccess,
        conversationId: String,
        sources: List<CollaborationSpaceInboxSource>,
    ): AppResult<CollaborationSpaceInboxActivities> {
        val retained = sources.toList()
        return withContext(Dispatchers.Default) {
            safeStorageCall {
                require(
                    validCollaborationMessageId(conversationId) &&
                        retained.size in 1..32 &&
                        retained.distinct().size == retained.size &&
                        retained.all(::validCollaborationSpaceInboxSource)
                )
                val scope = spaceHistoryScope(access)
                val partition = spaceInboxPartition(access)
                if (replicas.observePublishedCheckpoint(scope, partition).first() == null)
                    return@safeStorageCall CollaborationSpaceInboxActivities(null, emptyList())
                keys.use { content, index ->
                    val codec = CollaborationSpaceInboxCodec(access, cipher, content, index)
                    val ids = retained.map { codec.id("activity", conversationId, it) }
                    // Finite exact-anchor acquisition; an unpublished generation cannot be exposed.
                    val page = replicas.workingDocuments(scope, partition, ids)
                    val raw = page.checkpoint
                    if (raw == null) {
                        require(page.documents.isEmpty())
                        return@use CollaborationSpaceInboxActivities(null, emptyList())
                    }
                    val version = codec.version(raw)
                    require(page.documents.size <= ids.size && page.documents.all { it.id in ids })
                    val values =
                        page.documents.map {
                            val value = codec.decode(raw, it)
                            require(value.conversationId == conversationId)
                            requireNotNull(value.activity).domain().also { activity ->
                                require(activity.source in retained)
                            }
                        }
                    CollaborationSpaceInboxActivities(version, values.toList())
                }
            }
        }
    }

    override suspend fun publish(
        access: CollaborationSpaceLocalAccess,
        publication: CollaborationSpaceInboxPublication,
    ): AppResult<Boolean> =
        when (val result = publishProjection(access, publication, false, null)) {
            is AppResult.Failed -> result
            is AppResult.Success ->
                AppResult.Success(result.value != CollaborationSpaceInboxSeedResult.CHANGED)
        }

    override suspend fun seed(
        access: CollaborationSpaceLocalAccess,
        publication: CollaborationSpaceInboxPublication,
        repair: CollaborationSpaceInboxRepairPublication?,
    ): AppResult<CollaborationSpaceInboxSeedResult> =
        publishProjection(access, publication, true, repair)

    /** One preparation path preserves exact anchors and commits optional repair demand jointly. */
    private suspend fun publishProjection(
        access: CollaborationSpaceLocalAccess,
        publication: CollaborationSpaceInboxPublication,
        seeding: Boolean,
        repair: CollaborationSpaceInboxRepairPublication?,
    ): AppResult<CollaborationSpaceInboxSeedResult> {
        val retained = publication.copy(activities = publication.activities.toList())
        return withContext(Dispatchers.Default) {
            safeStorageCall {
                require(validCollaborationSpaceInboxPublication(retained))
                require(
                    !seeding ||
                        (retained.repair is CollaborationSpaceInboxRepairChange.Retain &&
                            retained.draft == null)
                )
                require(repair == null || validCollaborationSpaceInboxRepairPublication(repair))
                if (seeding)
                    require(
                        validCollaborationSpaceMessageIntent(
                            access,
                            (retained.repair as CollaborationSpaceInboxRepairChange.Retain).intent,
                        )
                    )
                val scope = spaceHistoryScope(access)
                val partition = spaceInboxPartition(access)
                val initial = replicas.checkpoint(scope, partition)
                if (initial?.published == false)
                    return@safeStorageCall CollaborationSpaceInboxSeedResult.CHANGED
                if (initial == null && retained.expected != null)
                    return@safeStorageCall CollaborationSpaceInboxSeedResult.CHANGED
                if (initial == null && seeding && repair == null)
                    return@safeStorageCall CollaborationSpaceInboxSeedResult.DEMAND_REQUIRED
                keys.use(create = initial == null) { content, index ->
                    val codec = CollaborationSpaceInboxCodec(access, cipher, content, index)
                    if (initial?.let { codec.version(it) } != retained.expected)
                        return@use CollaborationSpaceInboxSeedResult.CHANGED
                    val conversationId = retained.entry.conversationId
                    val ids =
                        buildList {
                                add(codec.id("entry", conversationId))
                                add(codec.id("draft", conversationId))
                                retained.repair?.let { change ->
                                    val original =
                                        when (change) {
                                            is CollaborationSpaceInboxRepairChange.Retain ->
                                                change.intent
                                            is CollaborationSpaceInboxRepairChange.Complete ->
                                                change.reference.intent
                                        }
                                    add(
                                        codec.id(
                                            "repair",
                                            conversationId,
                                            CollaborationSpaceInboxSource.GroupOriginal(original.id),
                                        )
                                    )
                                }
                                retained.entry.newest?.let {
                                    add(codec.id("activity", conversationId, it.source))
                                }
                                retained.activities.forEach {
                                    add(codec.id("activity", conversationId, it.source))
                                }
                            }
                            .distinct()
                    val current = replicas.workingDocuments(scope, partition, ids)
                    if (current.checkpoint != initial)
                        return@use CollaborationSpaceInboxSeedResult.CHANGED
                    val mutation = codec.mutation(initial, current.documents, retained)
                    currentCoroutineContext().ensureActive()
                    if (mutation == null) {
                        return@use if (replicas.checkpoint(scope, partition) == initial)
                            CollaborationSpaceInboxSeedResult.RETAINED
                        else CollaborationSpaceInboxSeedResult.CHANGED
                    }
                    if (seeding && repair == null)
                        return@use CollaborationSpaceInboxSeedResult.DEMAND_REQUIRED
                    val mutations = mutableListOf(mutation)
                    if (repair != null) {
                        val progressPartition = spaceInboxRepairProgressPartition(access)
                        val raw = replicas.checkpoint(scope, progressPartition)
                        val progressCodec =
                            CollaborationSpaceInboxRepairProgressCodec(access, cipher, content)
                        if (raw?.let { progressCodec.decode(it) } != repair.expected)
                            return@use CollaborationSpaceInboxSeedResult.CHANGED
                        mutations +=
                            ReplicaMutation.Replace(
                                progressPartition,
                                raw,
                                ReplicaSnapshot(
                                    repair.replacement.generation,
                                    progressCodec.encode(repair.replacement),
                                    emptyList(),
                                ),
                            )
                    }
                    currentCoroutineContext().ensureActive()
                    if (replicas.mutate(scope, mutations)) CollaborationSpaceInboxSeedResult.STORED
                    else CollaborationSpaceInboxSeedResult.CHANGED
                }
            }
        }
    }
}
