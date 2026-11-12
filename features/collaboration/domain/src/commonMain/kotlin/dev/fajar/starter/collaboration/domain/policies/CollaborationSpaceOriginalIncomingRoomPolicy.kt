package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

/** Structural local capture only. Current registration/source/admission are use-case inputs. */
fun validCollaborationSpaceOriginalIncomingRoom(
    runtime: CollaborationSpaceRuntimeReady,
    value: CollaborationSpaceOriginalIncomingRoom,
): Boolean {
    val target = value.captured.target as? CollaborationSpaceIncomingTarget.Original ?: return false
    val room = value.conversation.room ?: return false
    val parent = runtime.owner.parent
    return validCollaborationSpaceAccountAccess(
        CollaborationSpaceAccountAccess(parent.sessionId, parent.audience, parent.accountId)
    ) &&
        validCollaborationSpaceConversationId(runtime.deviceId) &&
        validCollaborationSpaceConversationId(runtime.operationId) &&
        validCollaborationSpaceIncomingRoomDemand(value.captured) &&
        validCollaborationSpaceOriginalConversation(
            value.conversation,
            runtime.owner,
            target.position,
        ) &&
        validCollaborationHistoryTraversal(value.traversal) &&
        validCollaborationSpaceConversationId(value.traversal.deviceId) &&
        value.traversal.conversationId == target.position.conversationId &&
        value.traversal.roomId == room.roomId &&
        value.traversal.purpose == CollaborationHistoryPurpose.Incoming
}

/** Content can consume retained references after a page commits, independently of provider EOF. */
fun validCollaborationSpaceOriginalIncomingContentRoom(
    runtime: CollaborationSpaceRuntimeReady,
    value: CollaborationSpaceOriginalIncomingRoom,
): Boolean =
    validCollaborationSpaceOriginalIncomingRoom(runtime, value) &&
        value.captured.phase in
            setOf(CollaborationSpaceIncomingPhase.Provider, CollaborationSpaceIncomingPhase.Pending)
