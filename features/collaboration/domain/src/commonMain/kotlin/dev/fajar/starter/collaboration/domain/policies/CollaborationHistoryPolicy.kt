package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

fun validCollaborationHistorySender(value: String) =
    value.startsWith('@') &&
        value.indexOf(':') > 1 &&
        value.length in 4..255 &&
        value.none { it.isWhitespace() || it.isISOControl() }

fun validCollaborationHistoryMessage(value: CollaborationHistoryMessage): Boolean =
    validCollaborationHistorySender(value.sender) &&
        (value.logicalId == null || validCollaborationMessageId(value.logicalId)) &&
        validCollaborationEventId(value.primaryEventId) &&
        value.timestamp in 0..9_007_199_254_740_991L &&
        (value.firstAcceptedAt == null || value.firstAcceptedAt in 0..value.timestamp) &&
        historyHash.matches(value.contentHash) &&
        (value.replyToEventId == null || validCollaborationEventId(value.replyToEventId)) &&
        value.eventIds.size in 1..128 &&
        value.eventIds.first() == value.primaryEventId &&
        value.eventIds.all(::validCollaborationEventId) &&
        value.eventIds.distinct().size == value.eventIds.size &&
        (if (value.redacted) value.text == null && value.latestEdit == null
        else value.text?.let(::validCollaborationText) == true) &&
        (value.latestEdit?.let { revision ->
            acceptsCollaborationMessageEdit(
                value.copy(latestEdit = null),
                CollaborationHistoryEventFact(
                    revision.edit.targetEventId,
                    value.sender,
                    revision.targetAcceptedAt,
                    false,
                ),
                revision.edit,
            )
        } ?: true)

/** Reject conflicting ID reuse; neither another sender nor different content can be coalesced. */
fun mergeCollaborationHistoryText(
    previous: CollaborationHistoryMessage?,
    event: CollaborationHistoryChange.Text,
    redactedBeforeOriginal: Boolean = false,
): CollaborationHistoryMessage? {
    val fresh =
        CollaborationHistoryMessage(
            event.sender,
            event.logicalId,
            event.eventId,
            event.timestamp,
            event.contentHash,
            event.text,
            listOf(event.eventId),
            false,
            replyToEventId = event.replyToEventId,
            forwarded = event.forwarded,
        )
    if (!validCollaborationHistoryMessage(fresh)) return null
    if (
        previous != null &&
            (!validCollaborationHistoryMessage(previous) ||
                previous.sender != event.sender ||
                previous.logicalId != event.logicalId ||
                previous.contentHash != event.contentHash ||
                (previous.forwarded && !event.forwarded) ||
                (previous.replyToEventId != null &&
                    previous.replyToEventId != event.replyToEventId) ||
                (!previous.redacted && previous.text != event.text) ||
                (event.logicalId == null && previous.primaryEventId != event.eventId))
    )
        return null
    val original = previous ?: fresh
    val deleted = original.redacted || redactedBeforeOriginal
    return original
        .copy(
            text = if (deleted) null else original.text,
            eventIds = (original.eventIds + event.eventId).distinct(),
            redacted = deleted,
            latestEdit = null,
            firstAcceptedAt =
                minOf(original.firstAcceptedAt ?: original.timestamp, event.timestamp),
            // An older reader may have retained the complete content hash before exposing replies.
            replyToEventId = original.replyToEventId ?: event.replyToEventId,
            forwarded = original.forwarded || event.forwarded,
        )
        .let { updated ->
            updated.copy(
                latestEdit =
                    original.latestEdit?.takeIf { revision ->
                        acceptsCollaborationMessageEdit(
                            updated,
                            CollaborationHistoryEventFact(
                                revision.edit.targetEventId,
                                updated.sender,
                                revision.targetAcceptedAt,
                                false,
                            ),
                            revision.edit,
                        )
                    }
            )
        }
        .takeIf(::validCollaborationHistoryMessage)
}

private val historyHash = Regex("[0-9a-f]{64}")
