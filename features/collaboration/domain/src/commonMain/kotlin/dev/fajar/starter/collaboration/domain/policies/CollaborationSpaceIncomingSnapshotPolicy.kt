package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

/** Match independently acquired transport to the original physical client, without a send lease. */
fun validCollaborationSpaceIncomingSnapshot(
    runtime: CollaborationSpaceRuntimeReady,
    binding: CollaborationSpaceRoomBinding,
    transport: CollaborationSpaceDirectTransport,
    snapshot: CollaborationSpaceEventSnapshot,
    eventId: String,
): Boolean {
    val physical = snapshot.physical
    val owner = physical.owner
    val parent = runtime.owner.parent
    if (
        !validCollaborationSpaceDirectTransport(transport) ||
            !validCollaborationSpaceRoomBinding(runtime.owner.localDirectoryAccess(), binding) ||
            transport.conversation.owner != runtime.owner ||
            transport.conversation.localMetadata() != binding.conversation ||
            transport.device.id != runtime.deviceId ||
            transport.device.origin != CollaborationSpaceDeviceOrigin.LEGACY ||
            transport.room != binding.room ||
            snapshot.runtime != runtime ||
            owner.sessionId != parent.sessionId ||
            owner.audience != parent.audience ||
            owner.accountId != parent.accountId ||
            !validCollaborationSpaceConversationId(owner.companyId) ||
            !validCollaborationSpaceConversationId(owner.nativeSessionId) ||
            !validCollaborationSpaceConversationId(physical.deviceId) ||
            physical.roomId != binding.room.roomId ||
            !physical.roomJoined ||
            !physical.roomEncrypted ||
            physical.event.eventId != eventId ||
            !validCollaborationEventId(eventId)
    )
        return false
    val sender =
        "@hris_${owner.companyId.replace("-", "")}_${owner.accountId.replace("-", "")}:${binding.room.serverName}"
    return transport.matrixUserId == sender &&
        transport.matrixDeviceId == "HRIS_${physical.deviceId.replace("-", "")}"
}
