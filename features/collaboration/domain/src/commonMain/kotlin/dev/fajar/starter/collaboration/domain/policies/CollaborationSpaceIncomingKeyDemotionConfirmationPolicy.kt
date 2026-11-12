package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

/** Confirms the unique recovery request that was atomically committed with primary removal/wake. */
fun confirmCollaborationSpaceIncomingKeyDemotion(
    access: CollaborationSpaceLocalAccess,
    previous: CollaborationSpaceIncomingDemandSnapshot,
    next: CollaborationSpaceIncomingDemand,
    captured: CollaborationSpaceIncomingRoomDemand,
    generation: String,
    written: AppResult<CollaborationSpaceIncomingDemandSnapshot?>,
    retained: CollaborationSpaceIncomingDemandSnapshot?,
): AppResult<Boolean> {
    val planned = planCollaborationSpaceIncomingKeyDemotion(previous.demand, captured, generation)
    if (
        planned !is AppResult.Success ||
            planned.value != next ||
            next == previous.demand ||
            !validCollaborationMessageId(previous.generation) ||
            !validCollaborationSpaceIncomingDemand(access, previous.demand)
    )
        return keyDemotionChanged()
    if (written is AppResult.Failed && written.failure.kind != FailureKind.Storage) return written
    if (written is AppResult.Success) {
        val receipt = written.value ?: return keyDemotionChanged()
        if (
            !validCollaborationMessageId(receipt.generation) ||
                receipt.generation == previous.generation ||
                receipt.demand != next
        )
            return keyDemotionChanged()
    }
    if (
        retained == null ||
            !validCollaborationMessageId(retained.generation) ||
            retained.generation == previous.generation ||
            !validCollaborationSpaceIncomingDemand(access, retained.demand)
    )
        return if (written is AppResult.Failed) written else keyDemotionChanged()
    val task = retained.demand.rooms.singleOrNull { it.target == captured.target }
    val recovery = retained.demand.historyRecovery
    if (
        task?.generation == captured.generation ||
            recovery == null ||
            (recovery.generation != generation && recovery.requestedGeneration != generation)
    )
        return if (written is AppResult.Failed) written else keyDemotionChanged()
    return AppResult.Success(true)
}

private fun keyDemotionChanged() =
    collaborationRecoveryFailure("client.collaboration_key_wait_changed", FailureKind.Conflict)
