package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

/** Local original scope only; this derives no content or delivery permission. */
fun collaborationSpaceRecoveryQueueAccess(
    runtime: CollaborationSpaceRuntimeReady,
    conversation: CollaborationSpaceConversation,
    binding: CollaborationSpaceRoomBinding,
    snapshot: CollaborationSpaceOriginalQueueSnapshot,
): AppResult<CollaborationLocalAccess> {
    val original = binding.original ?: return invalidCollaborationSpaceRecoveryDemand()
    if (
        runtime.owner != conversation.owner ||
            !validCollaborationSpaceRoomBinding(runtime.owner.localDirectoryAccess(), binding) ||
            binding.conversation != conversation.localMetadata()
    )
        return invalidCollaborationSpaceRecoveryDemand()
    val position = original.conversation.position
    val sender =
        "@hris_${position.companyId.replace("-", "")}_${runtime.owner.parent.accountId.replace("-", "")}:${original.room.serverName}"
    val matched =
        collaborationOriginalQueueLogicalIds(
            runtime,
            CollaborationSpaceOriginalConversation(
                runtime.owner,
                original.conversation,
                original.room,
            ),
            sender,
            snapshot,
        )
    if (matched is AppResult.Failed) return matched
    return AppResult.Success(
        CollaborationLocalAccess(
            runtime.owner.parent.sessionId,
            snapshot.original.binding.audience,
            snapshot.original.binding.accountId,
            position.companyId,
        )
    )
}

internal fun invalidCollaborationSpaceRecoveryDemand() =
    collaborationRecoveryFailure(
        "client.collaboration_space_recovery_demand_changed",
        FailureKind.Conflict,
    )
