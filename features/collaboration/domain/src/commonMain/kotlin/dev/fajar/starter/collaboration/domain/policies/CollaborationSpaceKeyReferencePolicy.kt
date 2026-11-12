package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

/** A historical batch cannot borrow primary seek semantics or another source's outcomes. */
fun planCollaborationSpaceKeyReferences(
    runtime: CollaborationSpaceRuntimeReady,
    history: CollaborationSpaceKeyHistory,
    source: CollaborationHistoryReferences,
    batch: CollaborationSpaceKeyPageBatch,
): AppResult<List<CollaborationHistoryReference>> {
    if (
        !validCollaborationSpaceKeyHistoryReferences(runtime, history, source) ||
            batch.runtime != runtime ||
            batch.history != history ||
            batch.references != source
    )
        return collaborationRecoveryFailure(
            "client.collaboration_space_key_completion_changed",
            FailureKind.Conflict,
        )
    return planCollaborationHistoryReferenceResolutions(source.events, batch.items)
}
