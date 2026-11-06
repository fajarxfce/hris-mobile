package dev.fajar.starter.collaboration.data.history

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.database.*

/** Bounded read/merge plan; the repository commits every changed record and alias atomically. */
internal class CollaborationHistoryBatch(
    internal val replicas: ReplicaStore,
    internal val scope: ReplicaScope,
    internal val partition: String,
    internal val previous: ReplicaCheckpoint?,
    internal val codec: CollaborationHistoryCodec,
) {
    internal val messages = mutableMapOf<String, CollaborationHistoryMessage>()
    internal val aliases = mutableMapOf<String, HistoryAliasDto>()
    internal val editEvidence = mutableMapOf<String, HistoryEditEvidenceDto>()
    internal val revisions = mutableMapOf<String, HistoryRevisionDto>()
    internal val removedRevisions = mutableMapOf<String, String>()
    var added: Long = 0
        private set

    suspend fun apply(changes: List<CollaborationHistoryChange>) {
        for (change in changes) {
            require(validCollaborationHistorySender(change.sender))
            val aliasId = codec.aliasId(change.eventId)
            val alias =
                aliases[aliasId] ?: raw(aliasId)?.let { codec.alias(requireNotNull(previous), it) }
            require(alias == null || alias.sender == change.sender)
            when (change) {
                is CollaborationHistoryChange.Text -> original(change, aliasId, alias)
                is CollaborationHistoryChange.Redacted -> redacted(change, aliasId, alias)
            }
        }
    }

    suspend fun documents(generation: String, revision: Long): List<ReplicaDocument> =
        messages.values.map { codec.message(generation, revision, it) } +
            aliases.values.map { codec.alias(generation, revision, it) } +
            revisions.values.map { codec.revision(generation, revision, it) } +
            editEvidence.values.map { codec.editEvidence(generation, revision, it) } +
            removedRevisions.map { (id, sort) -> ReplicaDocument(id, revision, sort, null) }

    suspend fun applyEdits(edits: List<CollaborationMessageEdit>) {
        val batch = CollaborationHistoryEditBatch(this)
        for (edit in edits) batch.apply(edit)
    }

    internal suspend fun alias(eventId: String): HistoryAliasDto? {
        val id = codec.aliasId(eventId)
        return aliases[id] ?: raw(id)?.let { codec.alias(requireNotNull(previous), it) }
    }

    private suspend fun original(
        change: CollaborationHistoryChange.Text,
        aliasId: String,
        alias: HistoryAliasDto?,
    ) {
        require(editEvidence(change.eventId) == null)
        require(alias?.replacement != true)
        val id = codec.messageId(change.sender, change.logicalId, change.eventId)
        require(alias?.messageId == null || alias.messageId == id)
        require(alias?.timestamp == null || alias.timestamp == change.timestamp)
        val old = message(id)?.let { CollaborationHistoryEditBatch(this).resolveOriginal(id, it) }
        require(alias?.messageId == null || old?.eventIds?.contains(change.eventId) == true)
        val merged =
            requireNotNull(mergeCollaborationHistoryText(old, change, alias?.redacted == true))
        val hidden =
            merged.locallyHidden ||
                (previous != null &&
                    hiddenInHistory(
                        replicas,
                        previous,
                        codec,
                        CollaborationMessageReference(
                            change.sender,
                            change.eventId,
                            change.logicalId,
                        ),
                    ))
        val next =
            merged.copy(locallyHidden = hidden).let { value ->
                if (old?.latestEdit != null && value.latestEdit == null && !value.redacted)
                    value.copy(latestEdit = CollaborationHistoryEditBatch(this).latest(id, value))
                else value
            }
        if (old == null) added++
        if (next != old) messages[id] = next
        val nextAlias =
            HistoryAliasDto(
                change.eventId,
                change.sender,
                id,
                change.timestamp,
                alias?.redacted == true,
            )
        if (nextAlias != alias) aliases[aliasId] = nextAlias
    }

    private suspend fun redacted(
        change: CollaborationHistoryChange.Redacted,
        aliasId: String,
        alias: HistoryAliasDto?,
    ) {
        editEvidence(change.eventId)?.let { require(it.sender == change.sender) }
        if (alias?.replacement == true) {
            CollaborationHistoryEditBatch(this).redact(change, aliasId, alias)
            return
        }
        alias?.messageId?.let { id ->
            val old = requireNotNull(message(id))
            require(old.sender == change.sender && change.eventId in old.eventIds)
            val next = old.copy(text = null, redacted = true, latestEdit = null)
            if (next != old) messages[id] = next
        }
        val nextAlias =
            alias?.copy(redacted = true)
                ?: HistoryAliasDto(change.eventId, change.sender, null, null, true)
        if (nextAlias != alias) aliases[aliasId] = nextAlias
    }

    internal suspend fun editEvidence(eventId: String): HistoryEditEvidenceDto? {
        val id = codec.editEvidenceId(eventId)
        return editEvidence[id] ?: raw(id)?.let { codec.editEvidence(requireNotNull(previous), it) }
    }

    internal suspend fun message(id: String) =
        messages[id] ?: raw(id)?.let { codec.message(requireNotNull(previous), it) }

    internal suspend fun raw(id: String) =
        replicas.document(scope, partition, id).also {
            require(
                it == null || (previous != null && it.id == id && it.version <= previous.revision)
            )
        }
}
