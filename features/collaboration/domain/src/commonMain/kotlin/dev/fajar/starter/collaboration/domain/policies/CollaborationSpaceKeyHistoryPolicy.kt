package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

/** Retained logical Space coordinates do not replace current physical SDK admission. */
fun validCollaborationSpaceKeyHistory(
    runtime: CollaborationSpaceRuntimeReady,
    history: CollaborationSpaceKeyHistory,
): Boolean =
    validCollaborationOriginalConversationOwner(runtime.owner) &&
        validCollaborationSpaceConversationId(runtime.deviceId) &&
        validCollaborationSpaceConversationId(runtime.operationId) &&
        validCollaborationSpaceIncomingSource(history.source) &&
        validCollaborationSpaceRoomBinding(runtime.owner.localDirectoryAccess(), history.binding) &&
        validCollaborationHistoryTraversal(history.traversal) &&
        validCollaborationSpaceConversationId(history.traversal.deviceId) &&
        history.traversal.conversationId == history.binding.conversation.id &&
        history.traversal.roomId == history.binding.room.roomId &&
        history.traversal.purpose == CollaborationHistoryPurpose.Incoming

/**
 * Provider identity belongs to retained history, not necessarily the currently installed device.
 */
fun validCollaborationSpaceKeyHistoryReferences(
    runtime: CollaborationSpaceRuntimeReady,
    history: CollaborationSpaceKeyHistory,
    references: CollaborationHistoryReferences,
): Boolean =
    validCollaborationSpaceKeyHistory(runtime, history) &&
        references.progress?.provider != null &&
        boundedCollaborationHistoryRecoveryReferences(references) == references

/**
 * Verify actual physical provenance while preserving the Space lane, including Original aliases.
 */
fun validCollaborationSpaceKeyHistorySnapshot(
    runtime: CollaborationSpaceRuntimeReady,
    history: CollaborationSpaceKeyHistory,
    registration: CollaborationSpaceRegistrationRecord,
    platform: CollaborationNativePlatform,
    snapshot: CollaborationSpaceEventSnapshot,
    eventId: String,
): Boolean {
    if (!validCollaborationSpaceKeyHistory(runtime, history) || !validCollaborationEventId(eventId))
        return false
    val sdk =
        registeredCollaborationSpaceOriginalIncomingSdk(
            runtime,
            history.source,
            registration,
            platform,
        ) ?: return false
    val parent = runtime.owner.parent
    val physical = snapshot.physical
    val owner =
        CollaborationOwner(
            parent.sessionId,
            sdk.binding.audience,
            sdk.binding.accountId,
            sdk.binding.companyId,
            sdk.binding.nativeSessionId,
        )
    return registration.command.provider.serverName == history.binding.room.serverName &&
        snapshot.runtime == runtime &&
        physical.owner == owner &&
        physical.deviceId == sdk.deviceId &&
        physical.roomId == history.binding.room.roomId &&
        physical.roomJoined &&
        physical.roomEncrypted &&
        physical.event.eventId == eventId
}
