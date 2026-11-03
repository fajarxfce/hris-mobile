package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

fun validCollaborationNotificationInputRecoveryState(
    value: CollaborationNotificationInputRecoveryState
): Boolean {
    val references = value.references
    if (
        !validCollaborationMessageId(value.cycleId) ||
            references.size !in 1..COLLABORATION_NOTIFICATION_INPUT_CAPACITY
    )
        return false
    if (references.any { !validCollaborationNotificationInputReference(it) }) return false
    val ids = references.map { it.id }
    if (ids != ids.distinct().sorted()) return false
    if (value.nextIndex !in 0..references.size) return false
    if (value.nextIndex == 0 && (value.inFlight || value.retryRequired)) return false
    if (value.finished && (value.nextIndex != references.size || value.inFlight)) return false
    return !value.continuationRequired || value.finished
}

fun validCollaborationNotificationInputRecoveryCursor(
    value: CollaborationNotificationInputRecoveryCursor
): Boolean =
    validCollaborationMessageId(value.generation) &&
        validCollaborationNotificationInputRecoveryState(value.state)
