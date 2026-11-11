package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

/** Inventory shares the existing retry schedule, including recovery from a backward clock. */
fun eligibleCollaborationSpaceInventory(
    source: CollaborationSpaceIncomingSourceProgress,
    now: Long,
): Boolean {
    if (
        now !in 0..9_007_199_254_440_991L ||
            !validCollaborationSpaceIncomingSourceProgress(source) ||
            source.inventoryGeneration == null
    )
        return false
    val retry = source.inventoryRetry ?: return true
    return now < retry.attemptedAtEpochMillis || now >= retry.eligibleAtEpochMillis
}

/**
 * A replay cannot extend backoff, move a cursor, replace a later loss or rewind a real position.
 */
fun deferCollaborationSpaceInventory(
    observed: CollaborationSpaceIncomingDemand,
    captured: CollaborationSpaceIncomingSourceProgress,
    now: Long,
): AppResult<CollaborationSpaceIncomingDemand> {
    if (
        !validCollaborationSpaceIncomingDemand(observed) ||
            !validCollaborationSpaceIncomingSourceProgress(captured) ||
            captured.inventoryGeneration == null
    )
        return invalidInventoryDeferral()
    val retry =
        nextCollaborationSpaceIncomingRetry(captured.inventoryRetry, now)
            ?: return invalidInventoryDeferral(FailureKind.Validation)
    val current =
        observed.copy(
            rooms = observed.rooms.toList(),
            physicalRooms = observed.physicalRooms.toList(),
            sources = observed.sources.toList(),
        )
    val source = current.sources.singleOrNull { it.source.storeId == captured.source.storeId }
    if (source != null && source.source != captured.source) return invalidInventoryDeferral()
    if (
        source == null ||
            source.inventoryGeneration != captured.inventoryGeneration ||
            source.inventoryAfterRoomId != captured.inventoryAfterRoomId ||
            source.inventoryRetry != captured.inventoryRetry
    )
        return AppResult.Success(current)
    if (!eligibleCollaborationSpaceInventory(source, now)) return AppResult.Success(current)
    val next =
        current.copy(
            sources =
                current.sources.map { if (it == source) it.copy(inventoryRetry = retry) else it }
        )
    return if (validCollaborationSpaceIncomingDemand(next)) AppResult.Success(next)
    else invalidInventoryDeferral()
}

/** Source cursor/retry is the witness; unrelated source positions and queue work may advance. */
fun confirmCollaborationSpaceInventoryDeferral(
    captured: CollaborationSpaceIncomingSourceProgress,
    deferred: CollaborationSpaceIncomingSourceProgress,
    retained: CollaborationSpaceIncomingDemand,
    casRejected: Boolean,
): AppResult<Boolean> {
    if (
        !validCollaborationSpaceIncomingSourceProgress(captured) ||
            captured.inventoryGeneration == null ||
            !validCollaborationSpaceIncomingSourceProgress(deferred) ||
            !validCollaborationSpaceIncomingDemand(retained) ||
            deferred.source != captured.source ||
            deferred.inventoryGeneration != captured.inventoryGeneration ||
            deferred.inventoryAfterRoomId != captured.inventoryAfterRoomId ||
            deferred.inventoryRetry == null ||
            deferred.inventoryRetry == captured.inventoryRetry
    )
        return invalidInventoryDeferral()
    if (
        nextCollaborationSpaceIncomingRetry(
            captured.inventoryRetry,
            deferred.inventoryRetry.attemptedAtEpochMillis,
        ) != deferred.inventoryRetry ||
            !eligibleCollaborationSpaceInventory(
                captured,
                deferred.inventoryRetry.attemptedAtEpochMillis,
            )
    )
        return invalidInventoryDeferral()
    val progress = retained.sources.singleOrNull { it.source.storeId == captured.source.storeId }
    if (progress != null && progress.source != captured.source) return invalidInventoryDeferral()
    if (
        progress != null &&
            progress.inventoryGeneration == deferred.inventoryGeneration &&
            progress.inventoryAfterRoomId == deferred.inventoryAfterRoomId &&
            progress.inventoryRetry == deferred.inventoryRetry
    )
        return AppResult.Success(true)
    if (!casRejected) return invalidInventoryDeferral()
    if (
        progress == null ||
            progress.inventoryGeneration != captured.inventoryGeneration ||
            progress.inventoryAfterRoomId != captured.inventoryAfterRoomId ||
            progress.inventoryRetry != captured.inventoryRetry
    )
        return AppResult.Success(false)
    return collaborationSpaceIncomingQueueContention()
}

private fun invalidInventoryDeferral(kind: FailureKind = FailureKind.Conflict) =
    collaborationRecoveryFailure("client.collaboration_inventory_defer_changed", kind)
