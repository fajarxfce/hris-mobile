package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

/** Verify the unchanged native page against the exact original capture, never relabel its owner. */
fun validCollaborationSpaceOriginalProviderPage(
    runtime: CollaborationSpaceRuntimeReady,
    room: CollaborationSpaceOriginalIncomingRoom,
    source: CollaborationSpaceIncomingSource,
    page: CollaborationSpaceProviderHistoryPage,
    previous: CollaborationHistoryProgress?,
    origin: CollaborationHistoryPageOrigin,
): Boolean {
    if (
        !validCollaborationSpaceOriginalIncomingRoom(runtime, room) ||
            room.captured.phase == CollaborationSpaceIncomingPhase.Pending ||
            origin !=
                (if (room.captured.phase == CollaborationSpaceIncomingPhase.Latest)
                    CollaborationHistoryPageOrigin.Latest
                else CollaborationHistoryPageOrigin.SavedPosition)
    )
        return false
    val physical = page.physical
    val parent = runtime.owner.parent
    val position = room.conversation.conversation.position
    val metadata = requireNotNull(room.conversation.room)
    val access =
        CollaborationLocalAccess(
            parent.sessionId,
            parent.audience,
            parent.accountId,
            position.companyId,
        )
    val sender =
        "@hris_${position.companyId.replace("-", "")}_${parent.accountId.replace("-", "")}:${metadata.serverName}"
    return validCollaborationSpaceIncomingSource(source) &&
        source.storeId == room.captured.sourceId &&
        page.runtime == runtime &&
        physical.owner.nativeSessionId == parent.nativeSessionId &&
        source.matrixUserId == sender &&
        source.matrixDeviceId == "HRIS_${room.traversal.deviceId.replace("-", "")}" &&
        validCollaborationProviderPage(physical, access, room.traversal) &&
        physical.requestedPosition ==
            (if (origin == CollaborationHistoryPageOrigin.Latest) null else previous?.next) &&
        physical.requestedUntilPosition ==
            collaborationHistoryUntilPosition(room.traversal, previous, origin) &&
        (previous == null || previous.provider == null)
}

/**
 * A retained original page uses Company progress; a Group provider frame is a different identity.
 */
fun validCollaborationSpaceOriginalProviderAcquisition(
    value: CollaborationSpaceOriginalProviderHistoryAcquisition
): Boolean =
    validCollaborationSpaceOriginalIncomingRoom(value.runtime, value.room) &&
        value.room.captured.phase != CollaborationSpaceIncomingPhase.Pending &&
        value.origin ==
            (if (value.room.captured.phase == CollaborationSpaceIncomingPhase.Latest)
                CollaborationHistoryPageOrigin.Latest
            else CollaborationHistoryPageOrigin.SavedPosition) &&
        value.progress.provider == null &&
        validCollaborationHistoryAcquisition(
            value.room.traversal,
            value.origin,
            value.previous,
            value.progress,
            value.references,
        )
