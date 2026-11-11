package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

/** Capture before suspension. No source handles, event bodies or mutable SDK lists cross here. */
fun captureCollaborationSpaceSyncBatch(
    value: CollaborationSpaceSyncBatch
): CollaborationSpaceSyncBatch? {
    if (value.rooms.size > 64) return null
    val captured = value.copy(rooms = value.rooms.toList())
    return captured.takeIf {
        it.rooms.size <= 64 &&
            validCollaborationSpaceIncomingSource(it.source) &&
            validCollaborationSpaceIncomingPosition(it.nextPosition) &&
            (it.previousPosition == null ||
                validCollaborationSpaceIncomingPosition(it.previousPosition)) &&
            it.rooms.map { room -> room.roomId }.distinct().size == it.rooms.size &&
            it.rooms.all { room ->
                validCollaborationSpaceIncomingRoomId(room.roomId) &&
                    (room.headEventId == null || validCollaborationEventId(room.headEventId))
            }
    }
}

/**
 * Only the serial current producer advances opaque positions. Lost responses and bounded room
 * overflow retain inventory demand in the same proposed transaction as the position itself.
 */
fun retainCollaborationSpaceSyncBatch(
    observed: CollaborationSpaceIncomingDemand,
    response: CollaborationSpaceSyncBatch,
    roomGenerations: List<String>,
    inventoryGeneration: String,
): AppResult<CollaborationSpaceIncomingBatchRetention> {
    if (!validCollaborationSpaceIncomingDemand(observed)) return invalidIncomingBatch()
    val batch =
        captureCollaborationSpaceSyncBatch(response)
            ?: return invalidIncomingBatch(FailureKind.Validation)
    val current =
        observed.copy(
            rooms = observed.rooms.toList(),
            physicalRooms = observed.physicalRooms.toList(),
            sources = observed.sources.toList(),
        )
    val previous = current.sources.singleOrNull { it.source.storeId == batch.source.storeId }
    if (previous != null && previous.source != batch.source) return invalidIncomingBatch()
    if (previous?.position == batch.nextPosition)
        return AppResult.Success(CollaborationSpaceIncomingBatchRetention(current, false))
    if (roomGenerations.size != batch.rooms.size)
        return invalidIncomingBatch(FailureKind.Validation)
    val generations = roomGenerations.toList()
    if (!validBatchGenerations(current, batch, generations, inventoryGeneration))
        return invalidIncomingBatch(FailureKind.Validation)
    val sources = current.sources.toMutableList()
    if (previous == null && sources.size == 64) {
        val referenced =
            current.rooms.mapNotNull { it.sourceId }.toSet() +
                current.physicalRooms.map { it.sourceId }
        val retired =
            sources.firstOrNull {
                it.inventoryGeneration == null && it.source.storeId !in referenced
            }
                ?: return collaborationRecoveryFailure(
                    "client.collaboration_incoming_source_capacity",
                    FailureKind.Unavailable,
                )
        sources.remove(retired)
    }
    val merged = mergeIncomingPhysicalHints(current, batch, generations)
    if (merged is AppResult.Failed) return merged
    val work = (merged as AppResult.Success).value
    val loss =
        previous == null ||
            batch.previousPosition == null ||
            previous.position != batch.previousPosition ||
            batch.inventoryRequired ||
            work.omitted
    val progress =
        CollaborationSpaceIncomingSourceProgress(
            batch.source,
            batch.nextPosition,
            if (loss) inventoryGeneration else previous.inventoryGeneration,
        )
    if (previous == null) sources += progress else sources[sources.indexOf(previous)] = progress
    val next =
        current.copy(
            nextPosition = work.nextPosition,
            physicalRooms = work.rooms,
            sources = sources.toList(),
        )
    return if (validCollaborationSpaceIncomingDemand(next))
        AppResult.Success(
            CollaborationSpaceIncomingBatchRetention(
                next,
                loss || work.rooms != current.physicalRooms,
            )
        )
    else invalidIncomingBatch()
}

private fun validBatchGenerations(
    current: CollaborationSpaceIncomingDemand,
    batch: CollaborationSpaceSyncBatch,
    generations: List<String>,
    inventoryGeneration: String,
): Boolean {
    if (generations.size != batch.rooms.size) return false
    val allocated = generations + inventoryGeneration
    val retained =
        current.rooms.map { it.generation } +
            current.physicalRooms.map { it.generation } +
            current.sources.mapNotNull { it.inventoryGeneration } +
            listOfNotNull(current.rescanGeneration, current.scan?.generation)
    return allocated.all(::validCollaborationMessageId) &&
        allocated.distinct().size == allocated.size &&
        retained.none { it in allocated }
}

private data class IncomingPhysicalBatchRetention(
    val rooms: List<CollaborationSpaceIncomingPhysicalRoomDemand>,
    val nextPosition: Long,
    val omitted: Boolean,
)

/** Same-head hints preserve in-flight work; changed hints keep their FIFO turn and backoff. */
private fun mergeIncomingPhysicalHints(
    current: CollaborationSpaceIncomingDemand,
    batch: CollaborationSpaceSyncBatch,
    generations: List<String>,
): AppResult<IncomingPhysicalBatchRetention> {
    val rooms = current.physicalRooms.toMutableList()
    var nextPosition = current.nextPosition
    var omitted = false
    for ((index, hint) in batch.rooms.withIndex()) {
        val offset =
            rooms.indexOfFirst { it.sourceId == batch.source.storeId && it.roomId == hint.roomId }
        if (offset >= 0) {
            val previous = rooms[offset]
            if (
                previous.headEventId != null &&
                    previous.headEventId == hint.headEventId &&
                    previous.membership == hint.membership &&
                    (previous.limited || !hint.limited)
            )
                continue
            rooms[offset] =
                previous.copy(
                    generation = generations[index],
                    headEventId = hint.headEventId,
                    membership = hint.membership,
                    limited = previous.limited || hint.limited,
                )
        } else if (rooms.size + current.rooms.size == 64) {
            omitted = true
        } else {
            if (nextPosition == 9_007_199_254_740_991L) return invalidIncomingBatch()
            rooms +=
                CollaborationSpaceIncomingPhysicalRoomDemand(
                    batch.source.storeId,
                    hint.roomId,
                    generations[index],
                    nextPosition++,
                    hint.headEventId,
                    hint.membership,
                    hint.limited,
                )
        }
    }
    return AppResult.Success(IncomingPhysicalBatchRetention(rooms.toList(), nextPosition, omitted))
}

private fun invalidIncomingBatch(kind: FailureKind = FailureKind.Conflict) =
    collaborationRecoveryFailure("client.collaboration_incoming_batch_changed", kind)
