package dev.fajar.starter.collaboration.data.history

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.database.*

/**
 * Indexed bounded local mutation plan. Originals and provider aliases remain authenticated facts.
 */
internal class CollaborationHistoryHideBatch(
    private val replicas: ReplicaStore,
    private val scope: ReplicaScope,
    private val partition: String,
    private val previous: ReplicaCheckpoint?,
    private val codec: CollaborationHistoryCodec,
) {
    private val rules = linkedMapOf<String, HiddenHistoryRule>()
    private val messages = linkedMapOf<String, CollaborationHistoryMessage>()
    val addedRules: Int
        get() = rules.size

    val changed: Boolean
        get() = rules.isNotEmpty() || messages.isNotEmpty()

    suspend fun apply(references: List<CollaborationMessageReference>) {
        require(references.size in 1..COLLABORATION_LOCAL_DELETE_LIMIT)
        require(references.distinct().size == references.size)
        for (reference in references) {
            require(validCollaborationMessageReference(reference))
            val aliasId = codec.aliasId(reference.eventId)
            val alias = raw(aliasId)?.let { codec.alias(requireNotNull(previous), it) }
            require(alias == null || alias.sender == reference.sender)
            val id =
                alias?.messageId
                    ?: codec.messageId(reference.sender, reference.logicalId, reference.eventId)
            val message =
                messages[id] ?: raw(id)?.let { codec.message(requireNotNull(previous), it) }
            if (message != null) {
                require(message.sender == reference.sender)
                require(reference.logicalId == null || message.logicalId == reference.logicalId)
                require(alias == null || reference.eventId in message.eventIds)
                if (!message.locallyHidden) messages[id] = message.copy(locallyHidden = true)
                message.logicalId?.let { retain(HiddenHistoryRule(message.sender, logicalId = it)) }
            }
            for (rule in hiddenHistoryRules(reference)) retain(rule)
        }
    }

    suspend fun documents(generation: String, revision: Long): List<ReplicaDocument> =
        rules.values.map { codec.hidden(generation, revision, it) } +
            messages.values.map { codec.message(generation, revision, it) }

    private suspend fun retain(rule: HiddenHistoryRule) {
        val id = codec.hiddenId(rule)
        val retained = rules[id]
        if (retained != null) {
            require(retained == rule)
            return
        }
        val row = raw(id)
        if (row == null) rules[id] = rule
        else require(codec.hidden(requireNotNull(previous), row) == rule)
    }

    private suspend fun raw(id: String): ReplicaDocument? =
        replicas.document(scope, partition, id).also {
            require(
                it == null || (previous != null && it.id == id && it.version <= previous.revision)
            )
        }
}
