package dev.fajar.starter.collaboration.data.history

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.validCollaborationMessageId
import dev.fajar.starter.database.*

/** Only UUID Company paths and explicit space/v1 paths describe history under this prefix. */
internal class CollaborationAccountIncomingHistoryLocatorPageMapper(
    private val scope: ReplicaScope,
    private val after: String?,
    private val limit: Int,
) {
    init {
        require(limit in 1..16)
        validateReplicaPartitionPage(scope, COLLABORATION_HISTORY_PREFIX, after, limit + 1)
        if (after != null) location(after)
    }

    fun map(observed: List<ReplicaCheckpoint>): CollaborationAccountIncomingHistoryLocatorPage {
        val rows = observed.toList()
        require(rows.size <= limit + 1)
        require(rows.zipWithNext().all { (a, b) -> a.handle.partition < b.handle.partition })
        val mapped =
            rows.map { row ->
                require(row.published && row.handle.scope == scope && row.revision >= 0)
                require(row.handle.generation.isNotBlank())
                require(after == null || row.handle.partition > after)
                location(row.handle.partition)
            }
        return CollaborationAccountIncomingHistoryLocatorPage(
            mapped.take(limit).filterNotNull(),
            if (rows.size > limit) rows[limit - 1].handle.partition else null,
        )
    }

    private fun location(partition: String): CollaborationAccountIncomingHistoryLocator? {
        validateReplicaKey(scope, partition)
        require(partition.startsWith(COLLABORATION_HISTORY_PREFIX))
        val parts = partition.removePrefix(COLLABORATION_HISTORY_PREFIX).split('/')
        val space = parts[0] == "space"
        if (!space && !validCollaborationMessageId(parts[0])) return null
        require(!space || parts.getOrNull(1) == "v1")
        val ownerAt = if (space) 2 else 0
        require(parts.size == ownerAt + 2 || parts.size == ownerAt + 4)
        val owner = parts[ownerAt]
        val conversation = parts[ownerAt + 1]
        require(validCollaborationMessageId(owner) && validCollaborationMessageId(conversation))
        if (parts.size == ownerAt + 2) return null
        require(parts[ownerAt + 2] in setOf("pages", "incoming-pages"))
        val device = parts[ownerAt + 3]
        require(validCollaborationMessageId(device))
        if (parts[ownerAt + 2] == "pages") return null
        val history = CollaborationIncomingHistoryLocator(conversation, device)
        return if (space) CollaborationAccountIncomingHistoryLocator.Space(owner, history)
        else CollaborationAccountIncomingHistoryLocator.Company(owner, history)
    }
}
