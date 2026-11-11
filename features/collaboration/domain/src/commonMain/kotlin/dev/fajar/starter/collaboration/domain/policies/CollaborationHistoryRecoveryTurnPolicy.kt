package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

/** A later request waits behind the active finite pass. Replayed identities never rewind it. */
fun planCollaborationHistoryRecoveryRequest(
    progress: CollaborationHistoryProgress,
    generation: String,
): CollaborationHistoryRecoveryPosition? {
    if (!validCollaborationHistoryProgress(progress) || progress.incoming == null) return null
    val fresh =
        CollaborationHistoryRecoveryPosition(
            generation,
            progress.references,
            complete = progress.references == 0L,
        )
    if (!validCollaborationHistoryRecoveryPosition(fresh, progress.references)) return null
    val active = progress.recovery ?: return fresh
    return when {
        generation == active.generation || generation == active.requestedGeneration -> active
        active.complete -> fresh
        else -> active.copy(requestedGeneration = generation)
    }
}

/** Later appends cannot extend a recovery pass; sparse rows may require one final empty page. */
fun boundedCollaborationHistoryRecoveryReferences(
    page: CollaborationHistoryReferences,
    limit: Int = 16,
): CollaborationHistoryReferences? {
    val progress = page.progress ?: return null
    val active = progress.recovery ?: return null
    if (
        limit !in 1..16 ||
            active.complete ||
            !validCollaborationPendingHistoryReferences(page, active.after, limit)
    )
        return null
    val events = page.events.takeWhile { it.sequence < active.through }
    val after =
        if (
            events.size == page.events.size &&
                events.lastOrNull()?.let { it.sequence + 1 < active.through } == true
        )
            page.after
        else null
    return page.copy(events = events, after = after)
}

/**
 * Plans an attempted finite page against current recovery state, retaining a newer request. The raw
 * store proves index coverage; the admitted operation proves each content outcome.
 */
fun planCollaborationHistoryRecoveryAdvance(
    current: CollaborationHistoryProgress,
    attempted: CollaborationHistoryReferences,
    unresolved: Int,
    limit: Int = 16,
): CollaborationHistoryRecoveryPosition? {
    val expected = attempted.progress ?: return null
    val captured = expected.recovery ?: return null
    val active = current.recovery ?: return null
    if (
        !validCollaborationHistoryProgress(current) ||
            boundedCollaborationHistoryRecoveryReferences(attempted, limit) != attempted ||
            unresolved !in 0..attempted.events.size ||
            current.generation != expected.generation ||
            current.provider != expected.provider ||
            current.revision < expected.revision ||
            current.references < expected.references ||
            (captured.requestedGeneration != null && active.requestedGeneration == null) ||
            active.copy(requestedGeneration = captured.requestedGeneration) != captured
    )
        return null
    val seen = active.unresolved || unresolved > 0
    if (attempted.after != null) return active.copy(after = attempted.after, unresolved = seen)
    val requested = active.requestedGeneration
    return if (requested == null) active.copy(after = null, complete = true, unresolved = seen)
    else
        CollaborationHistoryRecoveryPosition(
            requested,
            current.references,
            complete = current.references == 0L,
        )
}
