package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

/** Confirms a recovery replacement already planned by the advance or defer policy. */
fun confirmCollaborationSpaceHistoryRecoveryWrite(
    access: CollaborationSpaceLocalAccess,
    previous: CollaborationSpaceIncomingDemandSnapshot,
    next: CollaborationSpaceHistoryRecovery,
    written: AppResult<CollaborationSpaceIncomingDemandSnapshot?>,
    retained: CollaborationSpaceIncomingDemandSnapshot?,
): AppResult<Boolean> {
    if (
        !validCollaborationMessageId(previous.generation) ||
            !validCollaborationSpaceIncomingDemand(access, previous.demand) ||
            !validCollaborationSpaceHistoryRecovery(next) ||
            next == previous.demand.historyRecovery
    )
        return recoveryWriteChanged()
    val demand = previous.demand.copy(historyRecovery = next)
    if (!validCollaborationSpaceIncomingDemand(access, demand)) return recoveryWriteChanged()
    if (written is AppResult.Failed && written.failure.kind != FailureKind.Storage) return written
    if (written is AppResult.Success) {
        val receipt = written.value ?: return recoveryWriteChanged()
        if (
            !validCollaborationMessageId(receipt.generation) ||
                receipt.generation == previous.generation ||
                receipt.demand != demand
        )
            return recoveryWriteChanged()
    }
    val current = retained?.demand?.historyRecovery
    if (
        retained == null ||
            !validCollaborationMessageId(retained.generation) ||
            retained.generation == previous.generation ||
            !validCollaborationSpaceIncomingDemand(access, retained.demand) ||
            current == null ||
            (current != next && !sameCollaborationSpaceHistoryRecoveryTurn(next, current))
    )
        return if (written is AppResult.Failed) written else recoveryWriteChanged()
    return AppResult.Success(true)
}

private fun recoveryWriteChanged() =
    collaborationRecoveryFailure(
        "client.collaboration_history_recovery_turn_changed",
        FailureKind.Conflict,
    )
