package dev.fajar.starter.collaboration.data.history

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.database.*

/** Bounded read/merge plan; the repository commits every changed record and alias atomically. */
internal class CollaborationHistoryBatch(
    private val replicas: ReplicaStore,
    private val scope: ReplicaScope,
    private val partition: String,
    private val previous: ReplicaCheckpoint?,
    private val codec: CollaborationHistoryCodec,
) {
    private val messages = mutableMapOf<String, CollaborationHistoryMessage>()
    private val aliases = mutableMapOf<String, HistoryAliasDto>()
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
            aliases.values.map { codec.alias(generation, revision, it) }

    private suspend fun original(
        change: CollaborationHistoryChange.Text,
        aliasId: String,
        alias: HistoryAliasDto?,
    ) {
        val id = codec.messageId(change.sender, change.logicalId, change.eventId)
        require(alias?.messageId == null || alias.messageId == id)
        require(alias?.timestamp == null || alias.timestamp == change.timestamp)
        val old = message(id)
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
        val next = merged.copy(locallyHidden = hidden)
        if (old == null) added++
        messages[id] = next
        aliases[aliasId] =
            HistoryAliasDto(
                change.eventId,
                change.sender,
                id,
                change.timestamp,
                alias?.redacted == true,
            )
    }

    private suspend fun redacted(
        change: CollaborationHistoryChange.Redacted,
        aliasId: String,
        alias: HistoryAliasDto?,
    ) {
        alias?.messageId?.let { id ->
            val old = requireNotNull(message(id))
            require(old.sender == change.sender && change.eventId in old.eventIds)
            messages[id] = old.copy(text = null, redacted = true)
        }
        aliases[aliasId] =
            alias?.copy(redacted = true)
                ?: HistoryAliasDto(change.eventId, change.sender, null, null, true)
    }

    private suspend fun message(id: String) =
        messages[id] ?: raw(id)?.let { codec.message(requireNotNull(previous), it) }

    private suspend fun raw(id: String) =
        replicas.document(scope, partition, id).also {
            require(
                it == null || (previous != null && it.id == id && it.version <= previous.revision)
            )
        }
}
