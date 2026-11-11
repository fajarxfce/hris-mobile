package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

/** Original Company progress must never be relabeled as a Published Group provider frame. */
fun planCollaborationSpaceOriginalIncomingReferences(
    runtime: CollaborationSpaceRuntimeReady,
    room: CollaborationSpaceOriginalIncomingRoom,
    source: CollaborationHistoryReferences,
    batch: CollaborationSpaceOriginalIncomingBatch?,
): AppResult<CollaborationSpaceIncomingReferencePlan> {
    val progress = source.progress ?: return invalidIncomingReferences()
    if (
        !validCollaborationSpaceOriginalIncomingRoom(runtime, room) ||
            room.captured.phase != CollaborationSpaceIncomingPhase.Pending ||
            progress.provider != null
    )
        return invalidIncomingReferences()
    if (
        source.events.isNotEmpty() &&
            (batch == null || batch.runtime != runtime || batch.room != room)
    )
        return invalidIncomingReferences()
    return planCollaborationIncomingReferenceOutcomes(source, batch?.items)
}

private fun invalidIncomingReferences() =
    collaborationRecoveryFailure(
        "client.collaboration_incoming_references_changed",
        FailureKind.Conflict,
    )
