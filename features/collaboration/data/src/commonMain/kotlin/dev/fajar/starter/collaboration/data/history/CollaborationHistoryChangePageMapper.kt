package dev.fajar.starter.collaboration.data.history

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.validCollaborationMessageId
import dev.fajar.starter.database.*

/** Authenticates one captured raw page; owns neither acquisition nor continuation policy. */
internal class CollaborationHistoryChangePageMapper(
    private val coordinates: CollaborationHistoryCoordinates,
    roomId: String,
) {
    private val owner = coordinates.changeOwner(roomId)
    private val recordId = Regex("[merph]-[0-9a-f]{64}")
    private val removedRevisionSort = Regex("[0-9]{16}/r-[0-9a-f]{64}")

    fun cursor(value: CollaborationHistoryChangeCursor?): ReplicaChangeCursor? =
        when (value) {
            null -> null
            is CollaborationHistoryChangeCursor.After -> {
                require(value.owner == owner)
                ReplicaChangeCursor.After(handle(value.generation), value.revision)
            }
            is CollaborationHistoryChangeCursor.Within -> {
                require(value.window.owner == owner && recordId.matches(value.recordId))
                ReplicaChangeCursor.Within(
                    ReplicaChangeWindow(
                        handle(value.window.generation),
                        value.window.afterRevision,
                        value.window.throughRevision,
                    ),
                    ReplicaChangePosition(value.revision, value.recordId),
                )
            }
        }

    suspend fun page(
        raw: ReplicaChangePage.Items,
        limit: Int,
        codec: CollaborationHistoryCodec,
    ): CollaborationHistoryChanges.Page {
        val checkpoint = raw.checkpoint
        val afterRevision = raw.window.afterRevision
        codec.checkpoint(checkpoint)
        require(raw.window.handle == checkpoint.handle)
        require(raw.window.throughRevision in 0..checkpoint.revision)
        require(afterRevision == null || afterRevision in 0..raw.window.throughRevision)
        require(
            raw.records.size <= limit &&
                raw.records.map { it.document.id }.distinct().size == raw.records.size
        )
        var previousRevision = -1L
        var previousId = ""
        val events =
            buildList {
                    for (changed in raw.records) {
                        val row = changed.document
                        require(recordId.matches(row.id) && row.version in 0..checkpoint.revision)
                        require(changed.changedAtRevision in 0..raw.window.throughRevision)
                        require(afterRevision == null || changed.changedAtRevision > afterRevision)
                        require(
                            changed.changedAtRevision > previousRevision ||
                                (changed.changedAtRevision == previousRevision &&
                                    row.id > previousId)
                        )
                        previousRevision = changed.changedAtRevision
                        previousId = row.id
                        reference(checkpoint, row, codec)?.let { add(it) }
                    }
                }
                .distinct()
        val window = window(raw.window)
        val next =
            raw.next?.let {
                require(raw.records.size == limit)
                val last = requireNotNull(raw.records.lastOrNull())
                require(it.window == raw.window)
                require(
                    it.position == ReplicaChangePosition(last.changedAtRevision, last.document.id)
                )
                CollaborationHistoryChangeCursor.Within(
                    window,
                    it.position.revision,
                    it.position.id,
                )
            }
        return CollaborationHistoryChanges.Page(
            window,
            checkpoint.handle.generation + ":" + checkpoint.revision,
            events,
            next,
        )
    }

    private suspend fun reference(
        checkpoint: ReplicaCheckpoint,
        row: ReplicaDocument,
        codec: CollaborationHistoryCodec,
    ): String? {
        if (row.payload == null) {
            // The existing writer removes only auxiliary revisions as raw tombstones. Originals
            // and their redaction/alias facts remain encrypted records in that same transaction.
            require(row.id.startsWith("r-") && row.tags.isEmpty())
            require(removedRevisionSort.matches(row.sortKey) && row.sortKey.endsWith("/" + row.id))
            return null
        }
        return when (row.id.first()) {
            'm' -> codec.message(checkpoint, row).primaryEventId
            'e' -> codec.alias(checkpoint, row).eventId
            'p' -> codec.editEvidence(checkpoint, row).targetEventId
            'r' -> codec.revision(checkpoint, row).revision.targetEventId
            'h' -> {
                codec.hidden(checkpoint, row)
                null
            }
            else -> error("Unsupported history record.")
        }
    }

    private fun handle(generation: String): ReplicaHandle {
        require(validCollaborationMessageId(generation))
        return ReplicaHandle(coordinates.scope, coordinates.partition, generation)
    }

    private fun window(value: ReplicaChangeWindow) =
        CollaborationHistoryChangeWindow(
            owner,
            value.handle.generation,
            value.afterRevision,
            value.throughRevision,
        )
}
