package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

/**
 * Plans one acquired event and at most one referenced target. Repositories retain original identity
 * and edit eligibility independently; the caller admits their account/room and I/O.
 */
fun planCollaborationIncomingHistory(
    event: CollaborationAcquiredEvent,
    target: CollaborationAcquiredEvent? = null,
    localOriginal: CollaborationIncomingOriginal? = null,
): AppResult<CollaborationIncomingHistoryPlan> {
    if (!validIncomingEvent(event) || (target != null && !validIncomingEvent(target)))
        return invalidIncomingHistory()
    val content = event.content
    val requested =
        when (content) {
            is CollaborationAcquiredEventContent.Replacement -> content.edit.targetEventId
            is CollaborationAcquiredEventContent.Redaction -> content.targetEventId
            else -> null
        }
    if (
        (target != null && target.eventId != requested) ||
            (localOriginal != null &&
                (content !is CollaborationAcquiredEventContent.Replacement || target != null))
    )
        return invalidIncomingHistory()
    val plan =
        when (content) {
            is CollaborationAcquiredEventContent.Original ->
                CollaborationIncomingHistoryPlan.Retain(listOf(content.message))
            is CollaborationAcquiredEventContent.Redacted ->
                CollaborationIncomingHistoryPlan.Retain(listOf(event.incomingTombstone()))
            is CollaborationAcquiredEventContent.Unresolved ->
                CollaborationIncomingHistoryPlan.AwaitDecryption(event.eventId, content.reason)
            CollaborationAcquiredEventContent.Unsupported ->
                CollaborationIncomingHistoryPlan.Unsupported
            is CollaborationAcquiredEventContent.Replacement -> {
                if (localOriginal != null) {
                    if (!validIncomingOriginal(localOriginal, content.edit.targetEventId))
                        return invalidIncomingHistory()
                    // The edit repository retains ineligible identity without changing the text.
                    CollaborationIncomingHistoryPlan.Retain(emptyList(), listOf(content.edit))
                } else if (target == null)
                    CollaborationIncomingHistoryPlan.AcquireTarget(content.edit.targetEventId)
                else
                    when (val original = target.content) {
                        is CollaborationAcquiredEventContent.Original ->
                            CollaborationIncomingHistoryPlan.Retain(
                                listOf(original.message),
                                listOf(content.edit),
                            )
                        is CollaborationAcquiredEventContent.Redacted ->
                            CollaborationIncomingHistoryPlan.MissingOriginal(
                                target.incomingTombstone()
                            )
                        is CollaborationAcquiredEventContent.Unresolved ->
                            CollaborationIncomingHistoryPlan.AwaitDecryption(
                                target.eventId,
                                original.reason,
                            )
                        else -> CollaborationIncomingHistoryPlan.Unsupported
                    }
            }
            is CollaborationAcquiredEventContent.Redaction -> {
                if (target == null)
                    CollaborationIncomingHistoryPlan.AcquireTarget(content.targetEventId)
                else
                    when (val original = target.content) {
                        is CollaborationAcquiredEventContent.Redacted ->
                            CollaborationIncomingHistoryPlan.Retain(
                                listOf(target.incomingTombstone())
                            )
                        is CollaborationAcquiredEventContent.Unresolved ->
                            CollaborationIncomingHistoryPlan.AwaitDecryption(
                                target.eventId,
                                original.reason,
                            )
                        is CollaborationAcquiredEventContent.Original,
                        is CollaborationAcquiredEventContent.Replacement ->
                            CollaborationIncomingHistoryPlan.AwaitRedaction(target.eventId)
                        else -> CollaborationIncomingHistoryPlan.Unsupported
                    }
            }
        }
    return AppResult.Success(plan)
}

private fun validIncomingEvent(event: CollaborationAcquiredEvent): Boolean {
    if (
        !validCollaborationEventId(event.eventId) ||
            !validCollaborationHistorySender(event.sender) ||
            event.timestamp !in 0..9_007_199_254_740_991L ||
            event.type.length !in 1..255 ||
            event.type.any { it.isWhitespace() || it.isISOControl() }
    )
        return false
    return when (val content = event.content) {
        is CollaborationAcquiredEventContent.Original ->
            event.type == "m.room.message" &&
                content.message.eventId == event.eventId &&
                content.message.sender == event.sender &&
                content.message.timestamp == event.timestamp &&
                mergeCollaborationHistoryText(null, content.message) != null
        is CollaborationAcquiredEventContent.Replacement ->
            event.type == "m.room.message" &&
                content.edit.eventId == event.eventId &&
                content.edit.sender == event.sender &&
                content.edit.timestamp == event.timestamp &&
                validCollaborationMessageEdit(content.edit)
        is CollaborationAcquiredEventContent.Redaction ->
            event.type == "m.room.redaction" &&
                validCollaborationEventId(content.targetEventId) &&
                content.targetEventId != event.eventId
        is CollaborationAcquiredEventContent.Redacted ->
            event.type in setOf("m.room.message", "m.room.encrypted") &&
                validCollaborationEventId(content.redactionEventId) &&
                content.redactionEventId != event.eventId
        is CollaborationAcquiredEventContent.Unresolved ->
            event.type in setOf("m.room.message", "m.room.encrypted")
        CollaborationAcquiredEventContent.Unsupported -> true
    }
}

private fun validIncomingOriginal(value: CollaborationIncomingOriginal, eventId: String): Boolean {
    val message = value.message
    val fact = value.fact
    val acceptedAt = fact.timestamp ?: return false
    return validCollaborationHistoryMessage(message) &&
        fact.eventId == eventId &&
        fact.eventId in message.eventIds &&
        fact.sender == message.sender &&
        acceptedAt in 0..9_007_199_254_740_991L &&
        (fact.eventId != message.primaryEventId || acceptedAt == message.timestamp) &&
        (!fact.redacted || message.redacted)
}

private fun CollaborationAcquiredEvent.incomingTombstone() =
    CollaborationHistoryChange.Redacted(eventId, sender)

private fun invalidIncomingHistory() =
    collaborationRecoveryFailure(
        "client.collaboration_invalid_incoming_history",
        FailureKind.Unexpected,
    )

/** Final retention outcome; only the owning acquisition may acknowledge storage. */
fun collaborationIncomingHistoryOutcome(
    plan: CollaborationIncomingHistoryPlan
): CollaborationIncomingHistoryOutcome =
    when (plan) {
        is CollaborationIncomingHistoryPlan.Retain ->
            CollaborationIncomingHistoryOutcome.Retained(
                plan.originals.map { it.eventId },
                plan.replacements.map { it.eventId },
            )
        is CollaborationIncomingHistoryPlan.AwaitDecryption ->
            CollaborationIncomingHistoryOutcome.AwaitDecryption(plan.eventId, plan.reason)
        is CollaborationIncomingHistoryPlan.AwaitRedaction ->
            CollaborationIncomingHistoryOutcome.AwaitRedaction(plan.eventId)
        is CollaborationIncomingHistoryPlan.MissingOriginal ->
            CollaborationIncomingHistoryOutcome.MissingOriginal(plan.tombstone.eventId)
        CollaborationIncomingHistoryPlan.Unsupported ->
            CollaborationIncomingHistoryOutcome.Unsupported
        is CollaborationIncomingHistoryPlan.AcquireTarget ->
            error("Target planning must finish before retention")
    }
