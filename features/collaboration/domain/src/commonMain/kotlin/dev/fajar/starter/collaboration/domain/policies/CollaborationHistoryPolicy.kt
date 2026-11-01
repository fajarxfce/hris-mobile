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
        historyHash.matches(value.contentHash) &&
        value.eventIds.size in 1..128 &&
        value.eventIds.first() == value.primaryEventId &&
        value.eventIds.all(::validCollaborationEventId) &&
        value.eventIds.distinct().size == value.eventIds.size &&
        if (value.redacted) value.text == null
        else value.text?.let(::validCollaborationText) == true

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
        )
    if (!validCollaborationHistoryMessage(fresh)) return null
    if (
        previous != null &&
            (!validCollaborationHistoryMessage(previous) ||
                previous.sender != event.sender ||
                previous.logicalId != event.logicalId ||
                previous.contentHash != event.contentHash ||
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
        )
        .takeIf(::validCollaborationHistoryMessage)
}

private val historyHash = Regex("[0-9a-f]{64}")
