package dev.fajar.starter.collaboration.data.history

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.database.*
import kotlinx.coroutines.flow.first

/** The repository owns key lifetime, final checkpoint equality and account admission. */
internal class CollaborationEditEvidenceReader(
    private val replicas: ReplicaStore,
    private val checkpoint: ReplicaCheckpoint,
    private val codec: CollaborationHistoryCodec,
) {
    suspend fun get(eventId: String): CollaborationEditEvidence? {
        val id = codec.editEvidenceId(eventId)
        return raw(id)?.let { resolve(codec.editEvidence(checkpoint, it)) }
    }

    suspend fun operation(sender: String, operationId: String): List<CollaborationEditEvidence> {
        val tag = codec.editOperationTag(sender, operationId)
        val page =
            replicas
                .observeQueries(
                    checkpoint.handle.scope,
                    checkpoint.handle.partition,
                    listOf(ReplicaQuery(tag, limit = 129)),
                )
                .first()
        require(page.checkpoint == checkpoint && page.pages.size == 1)
        val values = page.pages.single()
        require(values.size <= 128) { "Replacement operation exceeds the bounded evidence window." }
        return values.map {
            val value = codec.editEvidence(checkpoint, it)
            require(value.sender == sender && value.operationId == operationId)
            resolve(value)
        }
    }

    private suspend fun resolve(value: HistoryEditEvidenceDto): CollaborationEditEvidence {
        val original = codec.message(checkpoint, requireNotNull(raw(value.messageId)))
        val targetId = codec.aliasId(value.targetEventId)
        val target = codec.alias(checkpoint, requireNotNull(raw(targetId)))
        val aliasId = codec.aliasId(value.eventId)
        val alias = raw(aliasId)?.let { codec.alias(checkpoint, it) }
        return value.toDomain(original, target, alias)
    }

    private suspend fun raw(id: String) =
        replicas.document(checkpoint.handle.scope, checkpoint.handle.partition, id).also {
            require(it == null || it.id == id)
        }
}
