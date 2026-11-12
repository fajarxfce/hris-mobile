package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.CollaborationSpaceRoomKeyBatch

/** Capture before suspension; owned hints grant no root, registration, or native authority. */
fun captureCollaborationSpaceRoomKeyBatch(
    value: CollaborationSpaceRoomKeyBatch
): CollaborationSpaceRoomKeyBatch? {
    if (value.roomIds.size > 64) return null
    val captured = value.copy(roomIds = value.roomIds.toList())
    return captured.takeIf {
        validCollaborationOriginalConversationOwner(it.runtime.owner) &&
            validCollaborationSpaceConversationId(it.runtime.deviceId) &&
            validCollaborationSpaceConversationId(it.runtime.operationId) &&
            validCollaborationSpaceIncomingSource(it.source) &&
            validCollaborationMessageId(it.receiptId) &&
            it.roomIds.size <= 64 &&
            (it.roomIds.isNotEmpty() || it.reconcileRequired) &&
            it.roomIds.distinct().size == it.roomIds.size &&
            it.roomIds.all(::validCollaborationSpaceIncomingRoomId)
    }
}
