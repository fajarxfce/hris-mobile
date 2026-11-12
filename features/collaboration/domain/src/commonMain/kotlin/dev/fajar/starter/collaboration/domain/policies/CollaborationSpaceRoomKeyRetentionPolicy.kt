package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

fun validCollaborationSpaceRoomKeyReceipt(value: CollaborationSpaceRoomKeyReceipt): Boolean =
    validCollaborationSpaceIncomingSource(value.source) &&
        validCollaborationSpaceConversationId(value.runtimeDeviceId) &&
        validCollaborationSpaceConversationId(value.runtimeOperationId) &&
        validCollaborationMessageId(value.receiptId)

/** Receipt capture supplies identity, not current root/registration or metadata authority. */
fun captureCollaborationSpaceRoomKeyReceipt(
    batch: CollaborationSpaceRoomKeyBatch
): CollaborationSpaceRoomKeyReceipt? =
    captureCollaborationSpaceRoomKeyBatch(batch)?.let {
        CollaborationSpaceRoomKeyReceipt(
            it.source,
            it.runtime.deviceId,
            it.runtime.operationId,
            it.receiptId,
        )
    }

/** Only the serial admitted owner chooses known-room targets or account fallback. */
fun planCollaborationSpaceRoomKeyRetention(
    previous: CollaborationSpaceIncomingDemandSnapshot?,
    receipt: CollaborationSpaceRoomKeyReceipt,
    roomIds: List<String>?,
): AppResult<CollaborationSpaceIncomingDemand> {
    if (!validCollaborationSpaceRoomKeyReceipt(receipt))
        return roomKeyRetentionChanged(FailureKind.Validation)
    val captured = previous?.let(::captureCollaborationSpaceIncomingDemandSnapshot)
    if (previous != null && (captured == null || !validCollaborationMessageId(captured.generation)))
        return roomKeyRetentionChanged()
    val current = captured?.demand ?: CollaborationSpaceIncomingDemand()
    if (!validCollaborationSpaceIncomingDemand(current)) return roomKeyRetentionChanged()
    // Exact replay may find completed or subsequently requested history; its durable witness
    // remains.
    if (current.roomKeyReceipt == receipt) return AppResult.Success(current)
    if (
        current.roomKeyReceipt?.receiptId == receipt.receiptId ||
            current.historyRecovery?.generation == receipt.receiptId ||
            current.historyRecovery?.requestedGeneration == receipt.receiptId
    )
        return roomKeyRetentionChanged()
    val requested =
        planCollaborationSpaceHistoryRecoveryRequest(
            current.historyRecovery,
            receipt.receiptId,
            roomIds,
        )
    if (requested is AppResult.Failed) return requested
    return AppResult.Success(
        current.copy(
            historyRecovery = (requested as AppResult.Success).value,
            roomKeyReceipt = receipt,
        )
    )
}

/**
 * Exact retained identity witnesses the atomic intent+wake transaction despite later worker
 * progress.
 */
fun confirmCollaborationSpaceRoomKeyRetention(
    access: CollaborationSpaceLocalAccess,
    previous: CollaborationSpaceIncomingDemandSnapshot?,
    receipt: CollaborationSpaceRoomKeyReceipt,
    roomIds: List<String>?,
    next: CollaborationSpaceIncomingDemand,
    written: AppResult<CollaborationSpaceIncomingDemandSnapshot?>,
    retained: CollaborationSpaceIncomingDemandSnapshot?,
): AppResult<Boolean> {
    val planned = planCollaborationSpaceRoomKeyRetention(previous, receipt, roomIds)
    if (
        planned !is AppResult.Success ||
            planned.value != next ||
            next == previous?.demand ||
            !validCollaborationSpaceIncomingDemand(access, next)
    )
        return roomKeyRetentionChanged()
    if (written is AppResult.Failed && written.failure.kind != FailureKind.Storage) return written
    if (written is AppResult.Success) {
        val saved = written.value ?: return roomKeyRetentionChanged()
        if (
            !validCollaborationMessageId(saved.generation) ||
                saved.generation == previous?.generation ||
                saved.demand != next
        )
            return roomKeyRetentionChanged()
    }
    if (
        retained == null ||
            !validCollaborationMessageId(retained.generation) ||
            retained.generation == previous?.generation ||
            !validCollaborationSpaceIncomingDemand(access, retained.demand) ||
            retained.demand.roomKeyReceipt != receipt
    )
        return if (written is AppResult.Failed) written else roomKeyRetentionChanged()
    return AppResult.Success(true)
}

private fun roomKeyRetentionChanged(kind: FailureKind = FailureKind.Conflict) =
    collaborationRecoveryFailure("client.collaboration_room_key_retention_changed", kind)
