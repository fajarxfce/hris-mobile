package dev.fajar.starter.collaboration.data.mappers

import dev.fajar.starter.collaboration.data.dto.SdkRoomKeyBatch
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*

/** Bounded raw-to-domain mapping; malformed locators retain explicit reconciliation. */
internal fun SdkRoomKeyBatch.toSpaceRoomKeyBatch(
    runtime: CollaborationSpaceRuntimeReady,
    source: CollaborationSpaceIncomingSource,
): AppResult<CollaborationSpaceRoomKeyBatch> {
    var loss = reconcileRequired || roomIds.size > 64
    val retained = mutableListOf<String>()
    for (room in roomIds.take(64)) {
        if (!validCollaborationSpaceIncomingRoomId(room) || room in retained) loss = true
        else retained += room
    }
    val batch =
        captureCollaborationSpaceRoomKeyBatch(
            CollaborationSpaceRoomKeyBatch(runtime, source, receiptId, retained.toList(), loss)
        )
    return if (batch != null) AppResult.Success(batch)
    else
        collaborationRecoveryFailure(
            "client.collaboration_invalid_room_key_batch",
            FailureKind.Validation,
        )
}
