package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

/**
 * Inspect the existing progress without requesting another generation. Scope, current metadata,
 * source admission and provenance of the successful page receipt remain the operation owner's job.
 */
fun planCollaborationHistoryRecoveryVisit(
    generation: String,
    before: CollaborationHistoryRecoveryRead,
    receipt: CollaborationHistoryRecoveryPageCompletion?,
    current: CollaborationHistoryProgress?,
): CollaborationHistoryRecoveryVisit? {
    if (!validCollaborationMessageId(generation)) return null
    val expected =
        when (before) {
            CollaborationHistoryRecoveryRead.Absent ->
                return if (receipt == null && current == null)
                    CollaborationHistoryRecoveryVisit(false, false)
                else null
            is CollaborationHistoryRecoveryRead.Complete -> before.progress
            is CollaborationHistoryRecoveryRead.Pending -> before.references.progress ?: return null
        }
    if (
        !validCollaborationHistoryProgress(expected) ||
            expected.incoming == null ||
            current == null ||
            !validCollaborationHistoryProgress(current) ||
            current.incoming == null ||
            current.generation != expected.generation ||
            current.provider != expected.provider ||
            current.revision < expected.revision ||
            current.references < expected.references ||
            current.recovery == null ||
            (current.revision == expected.revision && current != expected)
    )
        return null
    val attempted = expected.recovery ?: return null
    when (before) {
        is CollaborationHistoryRecoveryRead.Complete -> {
            if (receipt != null || !attempted.complete || attempted.generation != generation)
                return null
            return CollaborationHistoryRecoveryVisit(false, attempted.unresolved)
        }
        is CollaborationHistoryRecoveryRead.Pending -> {
            val page = before.references
            if (
                boundedCollaborationHistoryRecoveryReferences(page, 16) != page ||
                    receipt == null ||
                    receipt.generation != attempted.generation ||
                    receipt.after != page.after ||
                    receipt.resolved !in 0..page.events.size ||
                    receipt.unresolved !in 0..page.events.size ||
                    receipt.resolved + receipt.unresolved != page.events.size ||
                    current.revision <= expected.revision
            )
                return null
            val retained = current.recovery ?: return null
            if (receipt.generation == generation && receipt.after == null) {
                // This exact pass finished, even if its transaction promoted a later request.
                return CollaborationHistoryRecoveryVisit(
                    false,
                    attempted.unresolved || receipt.unresolved > 0,
                )
            }
            if (retained.generation == generation && retained.complete)
                return CollaborationHistoryRecoveryVisit(false, retained.unresolved)
            return CollaborationHistoryRecoveryVisit(
                true,
                retained.generation == generation && retained.unresolved,
            )
        }
        CollaborationHistoryRecoveryRead.Absent -> return null
    }
}
