package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

/**
 * Schedule a new Original turn from an admitted Published LEGACY alias. No saved Space provider
 * phase becomes Company progress. Source progress must already come from a real sync response.
 */
fun routeCollaborationSpaceIncomingAlias(
    observed: CollaborationSpaceIncomingDemand,
    captured: CollaborationSpaceIncomingRoomDemand,
    source: CollaborationSpaceIncomingSource,
    target: CollaborationSpaceIncomingTarget.Original,
    generation: String,
): AppResult<CollaborationSpaceIncomingAliasResolution> {
    if (
        !validCollaborationSpaceIncomingDemand(observed) ||
            !validCollaborationSpaceIncomingRoomDemand(captured) ||
            captured.target !is CollaborationSpaceIncomingTarget.Published ||
            !validCollaborationSpaceIncomingSource(source) ||
            !validCollaborationSpaceIncomingTarget(target) ||
            !validCollaborationMessageId(generation) ||
            (captured.sourceId != null && captured.sourceId != source.storeId)
    )
        return invalidAliasRouting()
    val current =
        observed.copy(
            rooms = observed.rooms.toList(),
            physicalRooms = observed.physicalRooms.toList(),
            sources = observed.sources.toList(),
        )
    val alias = current.rooms.singleOrNull { it.target == captured.target }
    if (alias == null || alias.generation != captured.generation)
        return AppResult.Success(
            CollaborationSpaceIncomingAliasResolution(
                current,
                CollaborationSpaceIncomingBindingResolution.Obsolete,
            )
        )
    if (alias != captured) return invalidAliasRouting()
    val progress = current.sources.singleOrNull { it.source.storeId == source.storeId }
    if (progress == null)
        return AppResult.Success(
            CollaborationSpaceIncomingAliasResolution(
                current,
                CollaborationSpaceIncomingBindingResolution.WaitingForOriginalSource,
            )
        )
    if (progress.source != source) return invalidAliasRouting()
    if (current.rooms.any { it.target == target })
        return AppResult.Success(
            CollaborationSpaceIncomingAliasResolution(
                current,
                CollaborationSpaceIncomingBindingResolution.WaitingForPrevious,
            )
        )
    val retainedGenerations =
        current.rooms.map { it.generation } +
            current.physicalRooms.map { it.generation } +
            current.sources.flatMap {
                listOfNotNull(it.inventoryGeneration, it.inventoryRequestedGeneration)
            } +
            listOfNotNull(current.rescanGeneration, current.scan?.generation)
    if (generation in retainedGenerations) return invalidAliasRouting()
    val converted =
        CollaborationSpaceIncomingRoomDemand(
            target,
            generation,
            captured.position,
            captured.headEventId,
            CollaborationSpaceIncomingPhase.Latest,
            retry = null,
            sourceId = source.storeId,
        )
    val next = current.copy(rooms = current.rooms.map { if (it == captured) converted else it })
    return if (validCollaborationSpaceIncomingDemand(next))
        AppResult.Success(
            CollaborationSpaceIncomingAliasResolution(
                next,
                CollaborationSpaceIncomingBindingResolution.Queued,
            )
        )
    else invalidAliasRouting()
}

/** Confirm the new exact task and removal of the old capture, independently of a write reply. */
fun confirmCollaborationSpaceIncomingAliasRouting(
    source: CollaborationSpaceIncomingSource,
    captured: CollaborationSpaceIncomingRoomDemand,
    converted: CollaborationSpaceIncomingRoomDemand,
    retained: CollaborationSpaceIncomingDemand,
    casRejected: Boolean,
): AppResult<CollaborationSpaceIncomingBindingResolution> {
    if (
        !validCollaborationSpaceIncomingSource(source) ||
            !validCollaborationSpaceIncomingRoomDemand(captured) ||
            captured.target !is CollaborationSpaceIncomingTarget.Published ||
            !validCollaborationSpaceIncomingRoomDemand(converted) ||
            converted.target !is CollaborationSpaceIncomingTarget.Original ||
            !validCollaborationSpaceIncomingDemand(retained) ||
            (captured.sourceId != null && captured.sourceId != source.storeId) ||
            converted.sourceId != source.storeId ||
            converted.generation == captured.generation ||
            converted.position != captured.position ||
            converted.headEventId != captured.headEventId ||
            converted.phase != CollaborationSpaceIncomingPhase.Latest ||
            converted.retry != null ||
            retained.sources.none { it.source == source }
    )
        return invalidAliasRouting()
    val previous = retained.rooms.singleOrNull { it.target == captured.target }
    if (
        retained.rooms.singleOrNull { it.generation == converted.generation } == converted &&
            previous?.generation != captured.generation
    )
        return AppResult.Success(CollaborationSpaceIncomingBindingResolution.Queued)
    if (!casRejected) return invalidAliasRouting()
    if (previous == null || previous.generation != captured.generation)
        return AppResult.Success(CollaborationSpaceIncomingBindingResolution.Obsolete)
    if (previous != captured) return invalidAliasRouting()
    return collaborationSpaceIncomingQueueContention()
}

private fun invalidAliasRouting() =
    collaborationRecoveryFailure(
        "client.collaboration_incoming_alias_changed",
        FailureKind.Conflict,
    )
