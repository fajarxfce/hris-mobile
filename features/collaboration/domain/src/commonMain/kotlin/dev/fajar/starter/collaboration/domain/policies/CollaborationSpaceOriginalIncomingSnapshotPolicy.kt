package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

/** The installed client must keep its original Company/device identity when reading an event. */
fun validCollaborationSpaceOriginalIncomingSnapshot(
    runtime: CollaborationSpaceRuntimeReady,
    room: CollaborationSpaceOriginalIncomingRoom,
    source: CollaborationSpaceIncomingSource,
    snapshot: CollaborationSpaceEventSnapshot,
    eventId: String,
): Boolean {
    if (
        !validCollaborationSpaceOriginalIncomingRoom(runtime, room) ||
            room.captured.phase != CollaborationSpaceIncomingPhase.Pending ||
            !validCollaborationSpaceIncomingSource(source) ||
            source.storeId != room.captured.sourceId ||
            !validCollaborationEventId(eventId)
    )
        return false
    val parent = runtime.owner.parent
    val company = room.conversation.conversation.position.companyId
    val metadata = requireNotNull(room.conversation.room)
    val physical = snapshot.physical
    val expected =
        CollaborationOwner(
            parent.sessionId,
            parent.audience,
            parent.accountId,
            company,
            parent.nativeSessionId,
        )
    val sender =
        "@hris_${company.replace("-", "")}_${parent.accountId.replace("-", "")}:${metadata.serverName}"
    return snapshot.runtime == runtime &&
        physical.owner == expected &&
        physical.deviceId == room.traversal.deviceId &&
        physical.roomId == metadata.roomId &&
        physical.roomJoined &&
        physical.roomEncrypted &&
        physical.event.eventId == eventId &&
        source.matrixUserId == sender &&
        source.matrixDeviceId == "HRIS_${room.traversal.deviceId.replace("-", "")}"
}
