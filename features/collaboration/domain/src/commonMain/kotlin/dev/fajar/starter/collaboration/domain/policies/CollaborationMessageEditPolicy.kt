package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

const val CollaborationMessageEditWindowMillis: Long = 15 * 60 * 1_000L

fun validCollaborationMessageEdit(value: CollaborationMessageEdit): Boolean =
    validCollaborationEventId(value.eventId) &&
        validCollaborationEventId(value.targetEventId) &&
        value.eventId != value.targetEventId &&
        validCollaborationHistorySender(value.sender) &&
        value.timestamp in 0..9_007_199_254_740_991L &&
        validCollaborationText(value.text) &&
        validCollaborationMessageOrigin(
            CollaborationMessageOrigin(value.sender, value.contentHash)
        ) &&
        (value.operationId == null || validCollaborationMessageId(value.operationId)) &&
        (value.operationId == null || value.operationId != value.originalLogicalId) &&
        (value.originalLogicalId == null || validCollaborationMessageId(value.originalLogicalId)) &&
        (value.replyToEventId == null || validCollaborationEventId(value.replyToEventId))

/**
 * Use the earlier retained server acceptance time, never device wall time. A later provider retry
 * cannot extend the logical original's edit window. The caller separately establishes room/account
 * ownership. This policy never changes local visibility.
 */
fun acceptsCollaborationMessageEdit(
    original: CollaborationHistoryMessage,
    target: CollaborationHistoryEventFact,
    edit: CollaborationMessageEdit,
): Boolean {
    val targetAcceptedAt = target.timestamp ?: return false
    val acceptedAt = minOf(original.firstAcceptedAt ?: original.timestamp, targetAcceptedAt)
    return validCollaborationHistoryMessage(original) &&
        validCollaborationMessageEdit(edit) &&
        !original.redacted &&
        !target.redacted &&
        target.eventId == edit.targetEventId &&
        target.eventId in original.eventIds &&
        edit.eventId !in original.eventIds &&
        target.sender == original.sender &&
        edit.sender == original.sender &&
        targetAcceptedAt in 0..9_007_199_254_740_991L &&
        (target.eventId != original.primaryEventId || targetAcceptedAt == original.timestamp) &&
        edit.timestamp >= targetAcceptedAt &&
        edit.timestamp - acceptedAt < CollaborationMessageEditWindowMillis &&
        edit.originalLogicalId == original.logicalId &&
        edit.replyToEventId == original.replyToEventId &&
        edit.forwarded == original.forwarded
}

/**
 * Deterministic order for already admitted replacements of one original. False is an older or
 * identical event; conflicting reuse of an event ID is rejected rather than silently accepted.
 * Room, sender, target and edit-window checks remain necessary before calling this helper.
 */
fun supersedesCollaborationMessageEdit(
    previous: CollaborationMessageEdit?,
    next: CollaborationMessageEdit,
): Boolean {
    require(validCollaborationMessageEdit(next))
    if (previous == null) return true
    require(validCollaborationMessageEdit(previous))
    if (previous.eventId == next.eventId) {
        require(previous == next) { "Replacement event identity cannot change." }
        return false
    }
    return next.timestamp > previous.timestamp ||
        (next.timestamp == previous.timestamp && next.eventId > previous.eventId)
}
