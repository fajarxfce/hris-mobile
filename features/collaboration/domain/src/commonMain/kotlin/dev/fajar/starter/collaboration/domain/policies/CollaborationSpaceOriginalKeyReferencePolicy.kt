package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

/** A historical batch cannot borrow primary seek semantics or another source's outcomes. */
fun planCollaborationSpaceOriginalKeyReferences(
    runtime: CollaborationSpaceRuntimeReady,
    history: CollaborationSpaceOriginalKeyHistory,
    source: CollaborationHistoryReferences,
    batch: CollaborationSpaceOriginalKeyPageBatch,
): AppResult<List<CollaborationHistoryReference>> {
    if (
        !validCollaborationSpaceOriginalKeyHistoryReferences(runtime, history, source) ||
            batch.runtime != runtime ||
            batch.history != history ||
            batch.references != source
    )
        return collaborationRecoveryFailure(
            "client.collaboration_original_key_completion_changed",
            FailureKind.Conflict,
        )
    return planCollaborationHistoryReferenceResolutions(source.events, batch.items)
}
