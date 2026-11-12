package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

/** Historical coordinates are structural facts; current registered source admission is separate. */
fun validCollaborationSpaceOriginalKeyHistory(
    runtime: CollaborationSpaceRuntimeReady,
    value: CollaborationSpaceOriginalKeyHistory,
): Boolean {
    val room = value.conversation.room ?: return false
    val position = value.conversation.conversation.position
    val parent = runtime.owner.parent
    return validCollaborationSpaceAccountAccess(
        CollaborationSpaceAccountAccess(parent.sessionId, parent.audience, parent.accountId)
    ) &&
        validCollaborationSpaceConversationId(runtime.deviceId) &&
        validCollaborationSpaceConversationId(runtime.operationId) &&
        validCollaborationSpaceIncomingSource(value.source) &&
        validCollaborationSpaceOriginalConversation(value.conversation, runtime.owner, position) &&
        validCollaborationHistoryTraversal(value.traversal) &&
        validCollaborationSpaceConversationId(value.traversal.deviceId) &&
        value.traversal.conversationId == position.conversationId &&
        value.traversal.roomId == room.roomId &&
        value.traversal.purpose == CollaborationHistoryPurpose.Incoming
}

/** A finite captured recovery page; structural validation never grants native admission. */
fun validCollaborationSpaceOriginalKeyHistoryReferences(
    runtime: CollaborationSpaceRuntimeReady,
    history: CollaborationSpaceOriginalKeyHistory,
    references: CollaborationHistoryReferences,
): Boolean =
    validCollaborationSpaceOriginalKeyHistory(runtime, history) &&
        references.progress?.provider == null &&
        boundedCollaborationHistoryRecoveryReferences(references) == references
