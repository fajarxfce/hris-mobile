package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

fun validCollaborationOriginalWindow(
    window: CollaborationOriginalWindow,
    owner: CollaborationOwner,
    deviceId: String,
    roomId: String,
    anchorEventId: String,
): Boolean {
    if (
        window.owner != owner ||
            window.deviceId != deviceId ||
            window.roomId != roomId ||
            window.anchorEventId != anchorEventId ||
            window.changes.size > 128 ||
            window.unresolvedEventIds.size > 128
    )
        return false
    val ids = window.changes.map { it.eventId } + window.unresolvedEventIds
    if (
        ids.size !in 1..128 ||
            ids.distinct().size != ids.size ||
            anchorEventId !in ids ||
            ids.any { !validCollaborationEventId(it) }
    )
        return false
    return window.changes.all {
        when (it) {
            is CollaborationHistoryChange.Text -> mergeCollaborationHistoryText(null, it) != null
            is CollaborationHistoryChange.Redacted -> validCollaborationHistorySender(it.sender)
        }
    }
}
