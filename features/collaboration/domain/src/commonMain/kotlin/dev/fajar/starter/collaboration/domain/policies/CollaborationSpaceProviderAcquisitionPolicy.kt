package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

/** Validates retained transition identity, never current runtime or content authority. */
fun validCollaborationSpaceProviderAcquisition(
    value: CollaborationSpaceProviderHistoryAcquisition
): Boolean {
    val runtime = value.runtime
    val parent = runtime.owner.parent
    val key = value.traversal
    val previous = value.previous
    val progress = value.progress
    val incoming = key.purpose == CollaborationHistoryPurpose.Incoming
    if (
        !validCollaborationSpaceAccountAccess(
            CollaborationSpaceAccountAccess(parent.sessionId, parent.audience, parent.accountId)
        ) ||
            !validCollaborationMessageId(runtime.owner.spaceId) ||
            !validCollaborationMessageId(parent.nativeSessionId) ||
            !validCollaborationMessageId(runtime.operationId) ||
            !validCollaborationHistoryTraversal(key) ||
            runtime.deviceId != key.deviceId ||
            value.references !in 0..20 ||
            !validCollaborationHistoryProgress(progress) ||
            progress.provider == null ||
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
    if (value.origin == CollaborationHistoryPageOrigin.SavedPosition && previous?.exhausted == true)
        return progress == previous && value.references == 0
    if (progress.revision != (previous?.revision?.plus(1) ?: 0L)) return false
    if (previous == null) {
        if (progress.references != value.references.toLong()) return false
    } else if (
        progress.references !in previous.references..(previous.references + value.references)
    )
        return false
    if (
        value.origin == CollaborationHistoryPageOrigin.SavedPosition &&
            previous?.next != null &&
            progress.next == previous.next
    )
        return false
    val position = progress.incoming
    return position == null ||
        (position.until == collaborationHistoryUntilPosition(key, previous, value.origin) &&
            (value.origin == CollaborationHistoryPageOrigin.Latest ||
                previous == null ||
                position.head == previous.incoming?.head) &&
            position.pendingAfter == previous?.incoming?.pendingAfter &&
            position.pendingThrough == previous?.incoming?.pendingThrough &&
            position.pendingRetryRequired == (previous?.incoming?.pendingRetryRequired ?: false))
}
