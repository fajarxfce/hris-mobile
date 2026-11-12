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
        !validCollaborationSpaceOriginalIncomingContentRoom(runtime, room) ||
            !validCollaborationSpaceIncomingSource(source) ||
            source.storeId != room.captured.sourceId ||
            !validCollaborationEventId(eventId)
    )
        return false
    return validOriginalPhysicalSnapshot(
        runtime,
        room.conversation,
        source,
        room.traversal.deviceId,
        snapshot,
        eventId,
    )
}

/** Snapshot device belongs to the current registered source, independently of history's device. */
fun validCollaborationSpaceOriginalKeyHistorySnapshot(
    runtime: CollaborationSpaceRuntimeReady,
    history: CollaborationSpaceOriginalKeyHistory,
    currentDeviceId: String,
    snapshot: CollaborationSpaceEventSnapshot,
    eventId: String,
): Boolean {
    if (
        !validCollaborationSpaceOriginalKeyHistory(runtime, history) ||
            !validCollaborationSpaceConversationId(currentDeviceId) ||
            !validCollaborationEventId(eventId)
    )
        return false
    return validOriginalPhysicalSnapshot(
        runtime,
        history.conversation,
        history.source,
        currentDeviceId,
        snapshot,
        eventId,
    )
}

private fun validOriginalPhysicalSnapshot(
    runtime: CollaborationSpaceRuntimeReady,
    conversation: CollaborationSpaceOriginalConversation,
    source: CollaborationSpaceIncomingSource,
    currentDeviceId: String,
    snapshot: CollaborationSpaceEventSnapshot,
    eventId: String,
): Boolean {
    val parent = runtime.owner.parent
    val company = conversation.conversation.position.companyId
    val metadata = requireNotNull(conversation.room)
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
        physical.deviceId == currentDeviceId &&
        physical.roomId == metadata.roomId &&
        physical.roomJoined &&
        physical.roomEncrypted &&
        physical.event.eventId == eventId &&
        source.matrixUserId == sender &&
        source.matrixDeviceId == "HRIS_${currentDeviceId.replace("-", "")}"
}
