package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

/** Raw local provenance only; current content/transport admission remains with the use case. */
fun validCollaborationSpaceProviderPage(
    page: CollaborationSpaceProviderHistoryPage,
    access: CollaborationSpaceLocalAccess,
    traversal: CollaborationHistoryTraversal,
): Boolean {
    val runtime = page.runtime
    val parent = runtime.owner.parent
    val physical = page.physical
    val owner = physical.owner
    return runtime.owner.localDirectoryAccess() == access &&
        runtime.deviceId == traversal.deviceId &&
        validCollaborationSpaceConversationId(runtime.deviceId) &&
        validCollaborationSpaceConversationId(runtime.operationId) &&
        validCollaborationSpaceConversationId(parent.nativeSessionId) &&
        owner.sessionId == parent.sessionId &&
        owner.audience == parent.audience &&
        owner.accountId == parent.accountId &&
        validCollaborationSpaceConversationId(owner.companyId) &&
        validCollaborationSpaceConversationId(owner.nativeSessionId) &&
        validCollaborationSpaceConversationId(physical.deviceId) &&
        validCollaborationProviderPage(
            physical,
            owner.localListAccess(),
            traversal.copy(deviceId = physical.deviceId),
        )
}

/** Current original transport and saved physical provenance must describe the same page. */
fun validCollaborationSpaceIncomingProviderPage(
    runtime: CollaborationSpaceRuntimeReady,
    binding: CollaborationSpaceRoomBinding,
    transport: CollaborationSpaceDirectTransport,
    page: CollaborationSpaceProviderHistoryPage,
    traversal: CollaborationHistoryTraversal,
    previous: CollaborationHistoryProgress?,
    origin: CollaborationHistoryPageOrigin,
): Boolean {
    val physical = page.physical
    val owner = physical.owner
    val provider = CollaborationHistoryProviderIdentity(owner.companyId, physical.deviceId)
    val sender =
        "@hris_${owner.companyId.replace("-", "")}_${owner.accountId.replace("-", "")}:${binding.room.serverName}"
    return validCollaborationSpaceProviderPage(
        page,
        runtime.owner.localDirectoryAccess(),
        traversal,
    ) &&
        validCollaborationSpaceRoomBinding(runtime.owner.localDirectoryAccess(), binding) &&
        validCollaborationSpaceDirectTransport(transport) &&
        transport.conversation.owner == runtime.owner &&
        transport.conversation.localMetadata() == binding.conversation &&
        transport.device.id == runtime.deviceId &&
        transport.device.origin == CollaborationSpaceDeviceOrigin.LEGACY &&
        transport.room == binding.room &&
        page.runtime == runtime &&
        traversal.conversationId == binding.conversation.id &&
        physical.roomId == binding.room.roomId &&
        physical.requestedPosition ==
            (if (origin == CollaborationHistoryPageOrigin.Latest) null else previous?.next) &&
        physical.requestedUntilPosition ==
            collaborationHistoryUntilPosition(traversal, previous, origin) &&
        (previous == null || previous.provider == provider) &&
        transport.matrixUserId == sender &&
        transport.matrixDeviceId == "HRIS_${physical.deviceId.replace("-", "")}"
}
