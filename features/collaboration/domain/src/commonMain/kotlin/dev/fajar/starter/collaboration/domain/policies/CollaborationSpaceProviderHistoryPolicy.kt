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
