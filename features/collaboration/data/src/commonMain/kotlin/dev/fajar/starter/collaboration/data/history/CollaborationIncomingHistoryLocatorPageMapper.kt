package dev.fajar.starter.collaboration.data.history

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.validCollaborationMessageId
import dev.fajar.starter.database.*

/** Maps published index coordinates; a locator grants no content or room-binding authority. */
internal class CollaborationIncomingHistoryLocatorPageMapper(
    private val scope: ReplicaScope,
    private val prefix: String,
    private val after: String?,
    private val limit: Int,
) {
    init {
        require(limit in 1..16)
        if (after != null) location(after)
    }

    fun map(observed: List<ReplicaCheckpoint>): CollaborationIncomingHistoryLocatorPage {
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
        return CollaborationIncomingHistoryLocatorPage(
            mapped.take(limit).filterNotNull(),
            if (rows.size > limit) rows[limit - 1].handle.partition else null,
        )
    }

    private fun location(partition: String): CollaborationIncomingHistoryLocator? {
        require(partition.length <= 384 && partition.startsWith(prefix))
        val parts = partition.removePrefix(prefix).split('/')
        require(parts.size == 1 || parts.size == 3)
        require(validCollaborationMessageId(parts[0]))
        if (parts.size == 1) return null
        require(parts[1] in setOf("pages", "incoming-pages"))
        require(validCollaborationMessageId(parts[2]))
        return if (parts[1] == "incoming-pages")
            CollaborationIncomingHistoryLocator(parts[0], parts[2])
        else null
    }
}
