package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

/** Original protected SDK stores use the complete canonical SHA-256 identity. */
fun validCollaborationSpaceIncomingSourceId(storeId: String): Boolean =
    storeId.length == 64 && storeId.all { it in '0'..'9' || it in 'a'..'f' }

/** Storage bounds only; matching an installed SDK and current admission belongs to the owner. */
fun validCollaborationSpaceIncomingSource(source: CollaborationSpaceIncomingSource): Boolean =
    validCollaborationSpaceIncomingSourceId(source.storeId) &&
        source.matrixUserId.length in 4..255 &&
        source.matrixUserId.startsWith('@') &&
        source.matrixUserId.indexOf(':') in 2 until source.matrixUserId.lastIndex &&
        source.matrixUserId.all { it.code in 33..126 } &&
        source.matrixDeviceId.length in 1..255 &&
        source.matrixDeviceId.all { it.code in 33..126 }

/** Provider positions are opaque. Validate their size and encoding without interpreting them. */
fun validCollaborationSpaceIncomingPosition(position: String): Boolean =
    position.length in 1..4096 &&
        runCatching { position.encodeToByteArray(throwOnInvalidSequence = true).size <= 4096 }
            .getOrDefault(false)

fun validCollaborationSpaceIncomingSourceProgress(
    progress: CollaborationSpaceIncomingSourceProgress
): Boolean =
    validCollaborationSpaceIncomingSource(progress.source) &&
        validCollaborationSpaceIncomingPosition(progress.position) &&
        (progress.inventoryGeneration == null ||
            validCollaborationMessageId(progress.inventoryGeneration)) &&
        (progress.inventoryAfterRoomId == null ||
            validCollaborationSpaceIncomingRoomId(progress.inventoryAfterRoomId)) &&
        (progress.inventoryRetry == null ||
            validCollaborationSpaceIncomingRetry(progress.inventoryRetry)) &&
        (progress.inventoryRequestedGeneration == null ||
            (validCollaborationMessageId(progress.inventoryRequestedGeneration) &&
                progress.inventoryRequestedGeneration != progress.inventoryGeneration)) &&
        (progress.inventoryGeneration != null ||
            (progress.inventoryAfterRoomId == null &&
                progress.inventoryRetry == null &&
                progress.inventoryRequestedGeneration == null))

fun validCollaborationSpaceIncomingPhysicalRoom(
    room: CollaborationSpaceIncomingPhysicalRoomDemand
): Boolean =
    validCollaborationSpaceIncomingSourceId(room.sourceId) &&
        validCollaborationSpaceIncomingRoomId(room.roomId) &&
        validCollaborationMessageId(room.generation) &&
        room.position in 0 until 9_007_199_254_740_991L &&
        (room.headEventId == null || validCollaborationEventId(room.headEventId)) &&
        (room.retry == null || validCollaborationSpaceIncomingRetry(room.retry))

fun validCollaborationSpaceIncomingRoomId(roomId: String): Boolean =
    roomId.startsWith('!') &&
        roomId.length in 2..255 &&
        roomId.none { it.isWhitespace() || it.isISOControl() } &&
        runCatching { roomId.encodeToByteArray(throwOnInvalidSequence = true) }.isSuccess
