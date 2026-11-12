package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

/**
 * Local metadata discovery is not an independent remote hint. Existing work for the exact
 * published, canonical or joined physical room already owns its discovery. No task is removed or
 * granted content authority here; source admission and physical resolution remain mandatory.
 */
fun undiscoveredCollaborationSpaceIncomingBindings(
    demand: CollaborationSpaceIncomingDemand,
    bindings: List<CollaborationSpaceRoomBinding>,
    original: CollaborationSpaceLegacySdk? = null,
): List<CollaborationSpaceRoomBinding> {
    val source =
        original?.let { legacy ->
            demand.sources
                .singleOrNull {
                    it.source.matrixUserId == legacy.identity.matrixUserId &&
                        it.source.matrixDeviceId == legacy.identity.matrixDeviceId
                }
                ?.source
        }
    return bindings.filter { binding ->
        demand.rooms.none { room ->
            when (val target = room.target) {
                is CollaborationSpaceIncomingTarget.Published ->
                    target.conversationId == binding.conversation.id
                is CollaborationSpaceIncomingTarget.Original ->
                    target.position == binding.original?.conversation?.position &&
                        target.position.companyId == original?.binding?.companyId &&
                        source != null &&
                        room.sourceId == source.storeId
            }
        } &&
            demand.physicalRooms.none { room ->
                room.roomId == binding.room.roomId &&
                    room.membership == CollaborationSpaceIncomingMembership.Joined &&
                    source != null &&
                    room.sourceId == source.storeId &&
                    (binding.original == null ||
                        binding.original.conversation.position.companyId ==
                            original?.binding?.companyId)
            }
    }
}
