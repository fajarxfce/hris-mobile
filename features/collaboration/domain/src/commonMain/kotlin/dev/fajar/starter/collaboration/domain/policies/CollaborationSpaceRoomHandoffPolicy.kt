package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

/** Local immutable linkage only; current native/device admission belongs to the owning flow. */
fun validCollaborationSpaceRoomHandoff(
    access: CollaborationSpaceLocalAccess,
    previous: CollaborationSpaceConversationCommandRecord,
    next: CollaborationSpaceConversationCommand.Room,
): Boolean {
    val original = previous.command as? CollaborationSpaceConversationCommand.Room ?: return false
    return validCollaborationSpaceConversationCommandRecord(access, previous) &&
        previous.successorId == null &&
        previous.outcome is CollaborationSpaceConversationOutcome.Fenced &&
        validCollaborationSpaceConversationCommand(access, next) &&
        next.predecessorId == original.id &&
        next.id != original.id &&
        next.id != original.predecessorId &&
        next.binding.audience == original.binding.audience &&
        next.binding.accountId == original.binding.accountId &&
        next.binding.spaceId == original.binding.spaceId &&
        next.intent.conversationId == original.intent.conversationId &&
        next.peerAccountId == original.peerAccountId
}
