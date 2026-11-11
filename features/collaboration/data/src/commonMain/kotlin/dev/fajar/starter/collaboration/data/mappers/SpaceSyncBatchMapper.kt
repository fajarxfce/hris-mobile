package dev.fajar.starter.collaboration.data.mappers

import dev.fajar.starter.collaboration.data.dto.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*

/** Reduce bounded raw locators to a physical head; unrepresentable hints require inventory. */
internal fun SdkSyncBatch.toSpaceSyncBatch(
    runtime: CollaborationSpaceRuntimeReady,
    source: CollaborationSpaceIncomingSource,
): AppResult<CollaborationSpaceSyncBatch> {
    var loss = rescanRequired || rooms.size > 64
    val retained = mutableListOf<CollaborationSpaceIncomingRoomHint>()
    val seen = mutableSetOf<String>()
    for (room in rooms.take(64)) {
        if (!validCollaborationSpaceIncomingRoomId(room.roomId) || !seen.add(room.roomId)) {
            loss = true
            continue
        }
        val rawHead = room.eventIds.lastOrNull()
        val head = rawHead?.takeIf(::validCollaborationEventId)
        if ((rawHead != null && head == null) || room.eventIds.size > 256) loss = true
        retained +=
            CollaborationSpaceIncomingRoomHint(
                room.roomId,
                head,
                when (room.membership) {
                    SdkSyncMembership.Joined -> CollaborationSpaceIncomingMembership.Joined
                    SdkSyncMembership.Left -> CollaborationSpaceIncomingMembership.Left
                },
                room.limited,
            )
    }
    val batch =
        captureCollaborationSpaceSyncBatch(
            CollaborationSpaceSyncBatch(
                runtime,
                source,
                previousBatch,
                nextBatch,
                retained.toList(),
                loss,
            )
        )
    return if (batch != null) AppResult.Success(batch)
    else
        collaborationRecoveryFailure(
            "client.collaboration_invalid_sync_batch",
            FailureKind.Validation,
        )
}
