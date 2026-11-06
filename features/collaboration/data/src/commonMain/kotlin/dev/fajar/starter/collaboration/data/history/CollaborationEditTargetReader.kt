package dev.fajar.starter.collaboration.data.history

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.database.*

/** The enclosing repository checks checkpoint/account again before returning this snapshot. */
internal class CollaborationEditTargetReader(
    private val replicas: ReplicaStore,
    private val checkpoint: ReplicaCheckpoint,
    private val codec: CollaborationHistoryCodec,
    private val roomId: String,
) {
    suspend fun get(eventId: String): CollaborationEditCandidate? {
        raw(codec.editEvidenceId(eventId))?.let {
            codec.editEvidence(checkpoint, it)
            return null
        }
        val alias = raw(codec.aliasId(eventId))?.let { codec.alias(checkpoint, it) } ?: return null
        if (alias.replacement || alias.redacted) return null
        val id = alias.messageId ?: return null
        val original = codec.message(checkpoint, requireNotNull(raw(id)))
        require(original.sender == alias.sender && eventId in original.eventIds)
        if (original.redacted || original.locallyHidden) return null
        val reference = CollaborationMessageReference(original.sender, eventId, original.logicalId)
        if (hiddenInHistory(replicas, checkpoint, codec, reference)) return null
        var earliest = original.timestamp
        for (originalId in original.eventIds) {
            val fact = codec.alias(checkpoint, requireNotNull(raw(codec.aliasId(originalId))))
            require(!fact.replacement && !fact.redacted)
            require(fact.messageId == id && fact.sender == original.sender)
            val time = requireNotNull(fact.timestamp)
            if (originalId == original.primaryEventId) require(time == original.timestamp)
            earliest = minOf(earliest, time)
        }
        require(original.firstAcceptedAt == null || original.firstAcceptedAt == earliest)
        val normalized = original.copy(firstAcceptedAt = earliest)
        require(validCollaborationHistoryMessage(normalized))
        val target =
            CollaborationEditTarget(
                roomId,
                original.primaryEventId,
                original.logicalId,
                original.sender,
                original.contentHash,
                original.timestamp,
                earliest,
                original.replyToEventId,
                original.forwarded,
            )
        require(validCollaborationEditTarget(target))
        return CollaborationEditCandidate(
            target,
            normalized.latestEdit?.edit?.text ?: requireNotNull(normalized.text),
        )
    }

    private suspend fun raw(id: String) =
        replicas.document(checkpoint.handle.scope, checkpoint.handle.partition, id).also {
            require(it == null || it.id == id)
        }
}
