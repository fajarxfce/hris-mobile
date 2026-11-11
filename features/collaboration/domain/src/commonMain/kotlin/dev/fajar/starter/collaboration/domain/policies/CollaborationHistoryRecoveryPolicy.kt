package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.CollaborationHistoryRecoveryPosition

/** Structural validity only; the requesting and advancing owners supply transition evidence. */
fun validCollaborationHistoryRecoveryPosition(
    position: CollaborationHistoryRecoveryPosition,
    references: Long,
): Boolean =
    references in 0..9_007_199_254_740_991L &&
        validCollaborationMessageId(position.generation) &&
        position.generation != "00000000-0000-0000-0000-000000000000" &&
        position.through in 0..references &&
        validCollaborationHistoryReferenceCursor(position.after) &&
        (!position.complete || (position.after == null && position.requestedGeneration == null)) &&
        (position.through != 0L || (position.complete && !position.unresolved)) &&
        (position.requestedGeneration?.let {
            validCollaborationMessageId(it) &&
                it != "00000000-0000-0000-0000-000000000000" &&
                it != position.generation
        } ?: true)
