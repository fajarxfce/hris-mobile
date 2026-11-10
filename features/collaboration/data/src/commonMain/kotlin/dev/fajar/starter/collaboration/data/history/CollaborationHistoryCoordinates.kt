package dev.fajar.starter.collaboration.data.history

import dev.fajar.starter.collaboration.data.messages.spaceMessageScope
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.validCollaborationHistoryTraversal
import dev.fajar.starter.collaboration.domain.policies.validCollaborationSpaceConversationId
import dev.fajar.starter.database.ReplicaScope

internal fun spaceHistoryScope(access: CollaborationSpaceLocalAccess) =
    spaceMessageScope(access).let { ReplicaScope(it.sessionId, it.accountKey) }

internal fun spaceHistoryPartition(
    access: CollaborationSpaceLocalAccess,
    conversationId: String,
): String {
    spaceHistoryScope(access)
    require(validCollaborationSpaceConversationId(conversationId))
    return COLLABORATION_HISTORY_PREFIX + "space/v1/" + access.spaceId + "/" + conversationId
}

/** Explicit authenticated namespaces; Company frames preserve their existing byte protocol. */
internal sealed class CollaborationHistoryCoordinates(
    val scope: ReplicaScope,
    val partition: String,
    private val namespace: String,
    private val audience: String,
    private val accountId: String,
    private val ownerId: String,
    private val conversationId: String,
) {
    class Company(access: CollaborationLocalAccess, conversationId: String) :
        CollaborationHistoryCoordinates(
            historyScope(access),
            historyPartition(access, conversationId),
            "hris-history",
            access.audience,
            access.accountId,
            access.companyId,
            conversationId,
        )

    class Space(access: CollaborationSpaceLocalAccess, conversationId: String) :
        CollaborationHistoryCoordinates(
            spaceHistoryScope(access),
            spaceHistoryPartition(access, conversationId),
            "hris-space-history",
            access.audience,
            access.accountId,
            access.spaceId,
            conversationId,
        )

    fun indexFrame(roomId: String) =
        listOf("$namespace-index-v1", audience, accountId, ownerId, conversationId, roomId)

    fun contentFrame(roomId: String) =
        listOf("$namespace-content-v1", audience, accountId, ownerId, conversationId, roomId)

    fun traversalPartition(traversal: CollaborationHistoryTraversal): String {
        require(validCollaborationHistoryTraversal(traversal))
        require(traversal.conversationId == conversationId)
        val lane =
            when (traversal.purpose) {
                CollaborationHistoryPurpose.Browse -> "/pages/"
                CollaborationHistoryPurpose.Incoming -> "/incoming-pages/"
            }
        return partition + lane + traversal.deviceId
    }

    fun traversalFrame(traversal: CollaborationHistoryTraversal): List<String> {
        traversalPartition(traversal)
        val prefix =
            when (this) {
                is Company -> "hris"
                is Space -> "hris-space"
            }
        val topic =
            when (traversal.purpose) {
                CollaborationHistoryPurpose.Browse -> "history"
                CollaborationHistoryPurpose.Incoming -> "incoming-history"
            }
        return listOf(
            "$prefix-$topic-pages-v1",
            audience,
            accountId,
            ownerId,
            conversationId,
            traversal.roomId,
            traversal.deviceId,
        )
    }

    override fun toString() = "CollaborationHistoryCoordinates([redacted])"
}
