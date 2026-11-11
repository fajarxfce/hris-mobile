package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

/** Validates retained transition identity, never current runtime or content authority. */
fun validCollaborationSpaceProviderAcquisition(
    value: CollaborationSpaceProviderHistoryAcquisition
): Boolean {
    val runtime = value.runtime
    val parent = runtime.owner.parent
    val key = value.traversal
    return validCollaborationSpaceAccountAccess(
        CollaborationSpaceAccountAccess(parent.sessionId, parent.audience, parent.accountId)
    ) &&
        validCollaborationMessageId(runtime.owner.spaceId) &&
        validCollaborationMessageId(parent.nativeSessionId) &&
        validCollaborationMessageId(runtime.operationId) &&
        runtime.deviceId == key.deviceId &&
        value.progress.provider != null &&
        validCollaborationHistoryAcquisition(
            key,
            value.origin,
            value.previous,
            value.progress,
            value.references,
        )
}
