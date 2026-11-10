package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

/** Complete local command facts; this neither grants transport nor infers room availability. */
fun collaborationSpaceMessageInventory(
    access: CollaborationSpaceLocalAccess,
    records: List<CollaborationSpaceMessageRecord>,
): List<CollaborationSpaceMessageRecord>? {
    if (
        !validCollaborationSpaceAccountAccess(
            CollaborationSpaceAccountAccess(access.sessionId, access.audience, access.accountId)
        ) ||
            !validCollaborationSpaceConversationId(access.accountId) ||
            !validCollaborationSpaceConversationId(access.spaceId) ||
            records.size > 128 ||
            records.map { it.intent.id }.distinct().size != records.size ||
            records.any { !validCollaborationSpaceMessageRecord(access, it) }
    )
        return null
    return records
        .map { record ->
            val state = record.state
            if (state is CollaborationMessageState.Staging)
                record.copy(state = state.copy(before = state.before.toSet()))
            else record
        }
        .sortedWith(compareBy({ it.intent.createdAt }, { it.intent.id }))
}
