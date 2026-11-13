package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

/**
 * Local immutable intent checks; no clock, selected company, credentials or transport admission.
 */
fun validCollaborationSpaceMessageIntent(
    access: CollaborationSpaceLocalAccess,
    intent: CollaborationSpaceMessageIntent,
): Boolean =
    validCollaborationSpaceAccountAccess(
        CollaborationSpaceAccountAccess(access.sessionId, access.audience, access.accountId)
    ) &&
        validCollaborationSpaceConversationId(access.accountId) &&
        validCollaborationSpaceConversationId(access.spaceId) &&
        intent.owner == access.messageOwner() &&
        validCollaborationSpaceMessageIntentContent(intent)

/** Payload validity alone grants no account ownership or access. */
fun validCollaborationSpaceMessageIntentContent(intent: CollaborationSpaceMessageIntent): Boolean {
    if (
        !validCollaborationSpaceConversationId(intent.id) ||
            !validCollaborationSpaceConversationId(intent.conversationId) ||
            intent.createdAt.toEpochMilliseconds() !in 0 until 253_402_300_800_000L ||
            !validCollaborationText(intent.text) ||
            intent.replyToEventId?.let(::validCollaborationReplyEventId) == false ||
            !validCollaborationSpaceEditInboxOrigin(intent)
    )
        return false
    val target = intent.editTarget ?: return true
    return validCollaborationEditTarget(target) &&
        intent.id != target.originalLogicalId &&
        intent.replyToEventId == target.replyToEventId &&
        intent.forwarded == target.forwarded
}

/** Newly authored organization commands cannot carry the legacy missing-origin exception. */
fun validCollaborationSpaceMessageRecord(
    access: CollaborationSpaceLocalAccess,
    record: CollaborationSpaceMessageRecord,
): Boolean {
    if (
        !validCollaborationSpaceMessageIntent(access, record.intent) ||
            record.revision !in 0..9_007_199_254_740_991L ||
            !validCollaborationMessageState(record.state)
    )
        return false
    val state = record.state
    val device =
        when (state) {
            CollaborationMessageState.Queued,
            CollaborationMessageState.Cancelled -> return record.revision == 0L
            is CollaborationMessageState.Staging -> state.device
            is CollaborationMessageState.Staged -> state.device
            is CollaborationMessageState.Sent -> state.device
        }
    if (
        record.revision == 0L ||
            state.origin == null ||
            !validCollaborationSpaceConversationId(device.nativeSessionId) ||
            !validCollaborationSpaceConversationId(device.deviceId) ||
            !validCollaborationSpaceConversationId(device.bootstrapOperationId)
    )
        return false
    val target = record.intent.editTarget ?: return true
    return device.roomId == target.roomId && state.origin?.sender == target.sender
}

/** Local cancellation cannot retire an uncertain or already-started native command. */
fun validCollaborationSpaceMessageCancellation(
    access: CollaborationSpaceLocalAccess,
    record: CollaborationSpaceMessageRecord,
): Boolean =
    validCollaborationSpaceMessageRecord(access, record) &&
        record.revision == 0L &&
        record.state == CollaborationMessageState.Queued

/** Storage validity; this predicate alone never grants native delivery or history retirement. */
fun validCollaborationSpaceMessageCompletion(
    access: CollaborationSpaceLocalAccess,
    completion: CollaborationSpaceMessageCompletion,
): Boolean {
    val record = completion.record
    if (!validCollaborationSpaceMessageRecord(access, record)) return false
    val sent = record.state as? CollaborationMessageState.Sent ?: return false
    val events = completion.eventIds
    return events.size in 1..128 &&
        events.distinct().size == events.size &&
        events.all(::validCollaborationEventId) &&
        sent.eventId in events
}
