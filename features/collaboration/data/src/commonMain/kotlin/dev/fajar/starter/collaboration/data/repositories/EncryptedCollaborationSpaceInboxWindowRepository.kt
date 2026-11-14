package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.data.inbox.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceInboxWindowRepository
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.storage.safeStorageCall
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.koin.core.annotation.*

@Single
class EncryptedCollaborationSpaceInboxWindowRepository(
    private val replicas: ReplicaStore,
    private val cipher: ContentCipher,
    @Named("collaboration-history-content-key") private val credentials: AtomicCredentialStore,
) : CollaborationSpaceInboxWindowRepository {
    private val keys = CollaborationHistoryKeys(replicas, cipher, credentials)

    override suspend fun read(
        access: CollaborationSpaceLocalAccess,
        anchor: CollaborationSpaceInboxWindowAnchor,
        limit: Int,
    ): AppResult<CollaborationSpaceInboxWindow> =
        withContext(Dispatchers.Default) {
            safeStorageCall {
                require(validCollaborationSpaceInboxWindowRequest(access, anchor, limit))
                val initial =
                    replicas
                        .observePublishedCheckpoint(
                            spaceHistoryScope(access),
                            spaceInboxPartition(access),
                        )
                        .first()
                if (initial == null)
                    return@safeStorageCall when (anchor) {
                        CollaborationSpaceInboxWindowAnchor.Latest ->
                            CollaborationSpaceInboxWindow.Items(null, emptyList(), null, null)
                        is CollaborationSpaceInboxWindowAnchor.Conversation ->
                            CollaborationSpaceInboxWindow.MissingAnchor
                        else -> CollaborationSpaceInboxWindow.Changed
                    }
                keys.use { content, index ->
                    Reading(access, CollaborationSpaceInboxCodec(access, cipher, content, index))
                        .read(anchor, limit)
                }
            }
        }

    /** One finite read borrows content keys and retains no observer or mutable projection. */
    private inner class Reading(
        val access: CollaborationSpaceLocalAccess,
        val codec: CollaborationSpaceInboxCodec,
    ) {
        val scope = spaceHistoryScope(access)
        val partition = spaceInboxPartition(access)

        suspend fun read(
            anchor: CollaborationSpaceInboxWindowAnchor,
            limit: Int,
        ): CollaborationSpaceInboxWindow =
            when (anchor) {
                CollaborationSpaceInboxWindowAnchor.Latest -> edge(null, false, limit)
                is CollaborationSpaceInboxWindowAnchor.Older -> edge(anchor.cursor, false, limit)
                is CollaborationSpaceInboxWindowAnchor.Newer -> edge(anchor.cursor, true, limit)
                is CollaborationSpaceInboxWindowAnchor.Conversation -> around(anchor.id, limit)
            }

        private suspend fun edge(
            cursor: String?,
            reverse: Boolean,
            limit: Int,
        ): CollaborationSpaceInboxWindow {
            val position = cursor?.let { codec.cursor(it) }
            val query =
                ReplicaQuery(
                    SPACE_INBOX_ENTRIES,
                    after = if (reverse) null else position?.after,
                    limit = limit + 1,
                    before = if (reverse) requireNotNull(position).after else null,
                )
            val page = replicas.observeQueries(scope, partition, listOf(query)).first()
            require(page.pages.size == 1)
            require(page.pages.single().size <= query.limit)
            val documents = page.pages.single().map { it.copy(tags = it.tags.toSet()) }
            val raw = page.checkpoint
            if (raw == null) {
                require(documents.isEmpty())
                return if (cursor == null)
                    CollaborationSpaceInboxWindow.Items(null, emptyList(), null, null)
                else CollaborationSpaceInboxWindow.Changed
            }
            val version = codec.version(raw)
            if (
                position != null &&
                    (position.generation != version.generation ||
                        position.revision != version.revision)
            )
                return CollaborationSpaceInboxWindow.Changed
            val candidates = decodeEntries(raw, documents, query)
            val selected = candidates.take(limit).let { if (reverse) it.reversed() else it }
            if (selected.isEmpty())
                return CollaborationSpaceInboxWindow.Items(version, emptyList(), null, null)
            return encodeWindow(
                version,
                selected,
                previous = if (reverse) candidates.size > limit else cursor != null,
                next = if (reverse) true else candidates.size > limit,
            )
        }

        private suspend fun around(id: String, limit: Int): CollaborationSpaceInboxWindow {
            val token = codec.id("entry", id)
            val probe = replicas.observeDocument(scope, partition, token).first()
            require(probe.documents.size <= 1 && probe.documents.all { it.id == token })
            val original = probe.documents.singleOrNull()?.let { it.copy(tags = it.tags.toSet()) }
            val first = probe.checkpoint
            if (first == null) {
                require(original == null)
                return CollaborationSpaceInboxWindow.MissingAnchor
            }
            codec.version(first)
            if (original == null) return CollaborationSpaceInboxWindow.MissingAnchor
            val originalValue = codec.decode(first, original)
            require(originalValue.entry != null && originalValue.conversationId == id)
            val newer =
                ReplicaQuery(SPACE_INBOX_ENTRIES, before = original.sortKey, limit = limit + 1)
            val center = ReplicaQuery(token, limit = 2)
            val older =
                ReplicaQuery(SPACE_INBOX_ENTRIES, after = original.sortKey, limit = limit + 1)
            val page =
                replicas.observeQueries(scope, partition, listOf(newer, center, older)).first()
            require(page.pages.size == 3)
            require(
                page.pages[0].size <= newer.limit &&
                    page.pages[1].size <= 1 &&
                    page.pages[2].size <= older.limit
            )
            val documents = page.pages.map { rows -> rows.map { it.copy(tags = it.tags.toSet()) } }
            val raw = page.checkpoint
            if (raw == null) {
                require(documents.all { it.isEmpty() })
                return CollaborationSpaceInboxWindow.MissingAnchor
            }
            val version = codec.version(raw)
            if (raw.handle.generation != first.handle.generation)
                return CollaborationSpaceInboxWindow.Changed
            val middle = documents[1]
            require(middle.size <= 1 && middle.all { it.id == token })
            val current =
                middle.singleOrNull() ?: return CollaborationSpaceInboxWindow.MissingAnchor
            val value = codec.decode(raw, current)
            require(value.conversationId == id)
            val entry = requireNotNull(value.entry).domain(id)
            if (current.sortKey != original.sortKey) return CollaborationSpaceInboxWindow.Changed
            val before = decodeEntries(raw, documents[0], newer)
            val after = decodeEntries(raw, documents[2], older)
            val firstCount = minOf(before.size, (limit - 1) / 2)
            val afterCount = minOf(after.size, limit - 1 - firstCount)
            val beforeCount = minOf(before.size, limit - 1 - afterCount)
            val selected =
                before.take(beforeCount).reversed() +
                    IndexedEntry(current, entry) +
                    after.take(afterCount)
            require(selected.map { it.entry.conversationId }.distinct().size == selected.size)
            return encodeWindow(
                version,
                selected,
                before.size > beforeCount,
                after.size > afterCount,
            )
        }

        private suspend fun decodeEntries(
            raw: ReplicaCheckpoint,
            documents: List<ReplicaDocument>,
            query: ReplicaQuery,
        ): List<IndexedEntry> {
            val after = query.after
            val before = query.before
            require(documents.size <= query.limit)
            require(documents.map { it.id }.distinct().size == documents.size)
            require(
                documents.zipWithNext().all { (a, b) ->
                    if (before == null) a.sortKey < b.sortKey else a.sortKey > b.sortKey
                }
            )
            require(
                documents.all {
                    (after == null || it.sortKey > after) && (before == null || it.sortKey < before)
                }
            )
            val values =
                documents.map { document ->
                    currentCoroutineContext().ensureActive()
                    val value = codec.decode(raw, document)
                    IndexedEntry(document, requireNotNull(value.entry).domain(value.conversationId))
                }
            require(values.map { it.entry.conversationId }.distinct().size == values.size)
            return values
        }

        private suspend fun encodeWindow(
            version: CollaborationSpaceInboxVersion,
            selected: List<IndexedEntry>,
            previous: Boolean,
            next: Boolean,
        ) =
            CollaborationSpaceInboxWindow.Items(
                version,
                selected.map { it.entry },
                if (previous) codec.cursor(version, selected.first().document.sortKey) else null,
                if (next) codec.cursor(version, selected.last().document.sortKey) else null,
            )
    }

    private data class IndexedEntry(
        val document: ReplicaDocument,
        val entry: CollaborationSpaceInboxEntry,
    )
}
