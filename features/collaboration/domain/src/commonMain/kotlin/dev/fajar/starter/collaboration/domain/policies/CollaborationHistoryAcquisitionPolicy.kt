package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

/**
 * Shared cursor transition rules. The owning policy separately checks Company or Space identity.
 */
fun validCollaborationHistoryAcquisition(
    traversal: CollaborationHistoryTraversal,
    origin: CollaborationHistoryPageOrigin,
    previous: CollaborationHistoryProgress?,
    progress: CollaborationHistoryProgress,
    references: Int,
): Boolean {
    val incoming = traversal.purpose == CollaborationHistoryPurpose.Incoming
    if (
        !validCollaborationHistoryTraversal(traversal) ||
            references !in 0..20 ||
            !validCollaborationHistoryProgress(progress) ||
            (progress.incoming != null) != incoming
    )
        return false
    if (
        previous != null &&
            (!validCollaborationHistoryProgress(previous) ||
                previous.provider != progress.provider ||
                previous.generation != progress.generation ||
                (previous.incoming != null) != incoming)
    )
        return false
    if (origin == CollaborationHistoryPageOrigin.SavedPosition && previous?.exhausted == true)
        return progress == previous && references == 0
    if (progress.revision != (previous?.revision?.plus(1) ?: 0L)) return false
    if (previous == null) {
        if (progress.references != references.toLong()) return false
    } else if (progress.references !in previous.references..(previous.references + references))
        return false
    if (
        origin == CollaborationHistoryPageOrigin.SavedPosition &&
            previous?.next != null &&
            progress.next == previous.next
    )
        return false
    val position = progress.incoming
    return position == null ||
        (position.until == collaborationHistoryUntilPosition(traversal, previous, origin) &&
            (origin == CollaborationHistoryPageOrigin.Latest ||
                previous == null ||
                position.head == previous.incoming?.head) &&
            position.pendingAfter == previous?.incoming?.pendingAfter &&
            position.pendingThrough == previous?.incoming?.pendingThrough &&
            position.pendingRetryRequired == (previous?.incoming?.pendingRetryRequired ?: false))
}
