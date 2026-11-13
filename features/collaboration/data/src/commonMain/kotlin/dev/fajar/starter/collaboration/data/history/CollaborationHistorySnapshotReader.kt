package dev.fajar.starter.collaboration.data.history

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.database.*

/** Bounded indexed acquisition under one authenticated checkpoint; no retry or admission policy. */
internal class CollaborationHistorySnapshotReader(
    private val replicas: ReplicaStore,
    private val checkpoint: ReplicaCheckpoint,
    private val codec: CollaborationHistoryCodec,
) {
    suspend fun read(eventIds: List<String>): List<CollaborationSpaceHistorySnapshotEvent> {
        val aliasIds = eventIds.associateWith { codec.aliasId(it) }
        val evidenceIds = eventIds.associateWith { codec.editEvidenceId(it) }
        val identities = readDocuments(aliasIds.values + evidenceIds.values)
        val aliases =
            aliasIds.mapValues { (_, id) -> identities[id]?.let { codec.alias(checkpoint, it) } }
        val evidence =
            evidenceIds.mapValues { (_, id) ->
                identities[id]?.let { codec.editEvidence(checkpoint, it) }
            }
        val targets =
            (evidence.values.filterNotNull().map { it.targetEventId } +
                    aliases.values.filterNotNull().mapNotNull { it.replacementTarget })
                .distinct()
                .associateWith { codec.aliasId(it) }
        val messageIds =
            (evidence.values.filterNotNull().map { it.messageId } +
                    aliases.values.filterNotNull().mapNotNull { it.messageId })
                .distinct()
        val retained = readDocuments(messageIds + targets.values)
        val originals =
            messageIds.associateWith { codec.message(checkpoint, requireNotNull(retained[it])) }
        val targetAliases =
            targets.mapValues { (_, id) -> codec.alias(checkpoint, requireNotNull(retained[id])) }
        val legacy =
            aliases.filter { (event, alias) ->
                evidence[event] == null && alias?.replacement == true && !alias.redacted
            }
        val revisionIds = legacy.keys.associateWith { codec.revisionId(it) }
        val revisions = readDocuments(revisionIds.values)
        return eventIds.map { event ->
            val alias = aliases[event]
            val proof = evidence[event]
            val id = proof?.messageId ?: alias?.messageId
            val original = id?.let { originals.getValue(it) }
            if (proof != null) {
                val message = requireNotNull(original)
                val target = targetAliases.getValue(proof.targetEventId)
                proof.toDomain(message, target, alias)
            } else if (alias != null && original != null) {
                require(alias.sender == original.sender)
                if (alias.replacement) {
                    val target = targetAliases.getValue(requireNotNull(alias.replacementTarget))
                    require(!target.replacement && target.messageId == id)
                    require(target.eventId in original.eventIds && target.sender == original.sender)
                    if (!alias.redacted) {
                        val revisionId = revisionIds.getValue(event)
                        val revision =
                            codec.revision(checkpoint, requireNotNull(revisions[revisionId]))
                        require(revision.messageId == id)
                        val edit = revision.revision.toDomain().edit
                        require(
                            edit.eventId == event &&
                                edit.sender == alias.sender &&
                                edit.timestamp == alias.timestamp
                        )
                        require(
                            edit.contentHash == alias.replacementHash &&
                                edit.targetEventId == alias.replacementTarget
                        )
                    }
                } else {
                    require(event in original.eventIds)
                    require(!alias.redacted || original.redacted)
                }
            }
            CollaborationSpaceHistorySnapshotEvent(event, original)
        }
    }

    private suspend fun readDocuments(ids: Collection<String>): Map<String, ReplicaDocument> {
        if (ids.isEmpty()) return emptyMap()
        val selected = ids.distinct()
        require(selected.size <= 64)
        val page =
            replicas.workingDocuments(
                checkpoint.handle.scope,
                checkpoint.handle.partition,
                selected,
            )
        if (page.checkpoint != checkpoint) throw CollaborationHistoryPreparationChanged()
        require(page.documents.map { it.id }.distinct().size == page.documents.size)
        require(page.documents.all { it.id in selected })
        return page.documents.filter { it.payload != null }.associateBy { it.id }
    }
}
