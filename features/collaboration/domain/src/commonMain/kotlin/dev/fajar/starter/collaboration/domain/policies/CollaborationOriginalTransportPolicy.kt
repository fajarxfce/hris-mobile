package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

/**
 * Matches a current transport observation to retained original coordinates. This pure match does
 * not grant delivery: the caller owns native admission, readiness and the physical lease.
 */
fun matchCollaborationOriginalTransport(
    runtime: CollaborationSpaceRuntimeReady,
    conversation: CollaborationSpaceConversation,
    binding: CollaborationSpaceRoomBinding,
    transport: CollaborationSpaceDirectTransport,
    snapshot: CollaborationSpaceOriginalQueueSnapshot,
    platform: CollaborationNativePlatform,
): AppResult<CollaborationSpaceOriginalConversation> {
    val original = binding.original ?: return originalTransportMismatch()
    if (
        runtime.owner != conversation.owner ||
            !validCollaborationSpaceRoomBinding(runtime.owner.localDirectoryAccess(), binding) ||
            binding.conversation != conversation.localMetadata() ||
            transport.conversation != conversation ||
            !validCollaborationSpaceDirectTransport(transport) ||
            transport.room != binding.room ||
            transport.device.id != runtime.deviceId ||
            transport.device.origin != CollaborationSpaceDeviceOrigin.LEGACY ||
            transport.device.platform != platform ||
            transport.matrixDeviceId !=
                "HRIS_${snapshot.original.device.deviceId.replace("-", "")}" ||
            snapshot.runtime != runtime
    )
        return originalTransportMismatch()
    val metadata =
        CollaborationSpaceOriginalConversation(runtime.owner, original.conversation, original.room)
    return when (
        val checked =
            collaborationOriginalQueueLogicalIds(
                runtime,
                metadata,
                transport.matrixUserId,
                snapshot,
            )
    ) {
        is AppResult.Failed -> checked
        is AppResult.Success -> AppResult.Success(metadata)
    }
}

private fun originalTransportMismatch() =
    collaborationSetupFailure("client.collaboration_original_transport_mismatch")
