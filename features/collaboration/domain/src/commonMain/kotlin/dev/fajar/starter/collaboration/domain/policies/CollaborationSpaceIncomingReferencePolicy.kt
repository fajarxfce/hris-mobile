package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

/** Published frames keep their Group receipt and retained physical provider identity. */
fun planCollaborationSpaceIncomingReferences(
    access: CollaborationSpaceLocalAccess,
    key: CollaborationHistoryTraversal,
    source: CollaborationHistoryReferences,
    batch: CollaborationSpaceIncomingBatch?,
): AppResult<CollaborationSpaceIncomingReferencePlan> {
    val progress = source.progress ?: return invalidIncomingReferences()
    if (
        key.purpose != CollaborationHistoryPurpose.Incoming ||
            !validCollaborationHistoryTraversal(key) ||
            progress.provider == null
    )
        return invalidIncomingReferences()
    if (
        source.events.isNotEmpty() &&
            (batch == null ||
                batch.runtime.owner.localDirectoryAccess() != access ||
                !validCollaborationSpaceConversationId(batch.runtime.operationId) ||
                batch.runtime.deviceId != key.deviceId ||
                batch.conversationId != key.conversationId ||
                batch.roomId != key.roomId ||
                batch.provider != progress.provider)
    )
        return invalidIncomingReferences()
    return planCollaborationIncomingReferenceOutcomes(source, batch?.items)
}

private fun invalidIncomingReferences() =
    collaborationRecoveryFailure(
        "client.collaboration_incoming_references_changed",
        FailureKind.Conflict,
    )
