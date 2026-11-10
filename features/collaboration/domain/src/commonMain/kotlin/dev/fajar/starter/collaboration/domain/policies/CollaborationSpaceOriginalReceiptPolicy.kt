package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

/** A missing local echo is only a recovery candidate, never proof of delivery. */
fun collaborationSpaceMissingOriginalReceipts(
    snapshot: CollaborationSpaceOriginalQueueSnapshot,
    records: List<CollaborationMessageRecord>,
): List<CollaborationMessageRecord> {
    val queued = snapshot.original.messages.mapNotNull { it.logicalId }.toSet()
    return records.filter { record ->
        val state = record.state as? CollaborationMessageState.Staged
        record.intent.id !in queued &&
            record.intent.editTarget == null &&
            state != null &&
            state.device == snapshot.original.device &&
            state.origin != null
    }
}

/** Match both namespaces before any original evidence can enter the physical receipt journal. */
fun validCollaborationSpaceOriginalHistory(
    history: CollaborationSpaceOriginalHistorySnapshot,
    queue: CollaborationSpaceOriginalQueueSnapshot,
): Boolean {
    val head = history.head
    if (
        history.runtime != queue.runtime ||
            head.owner.sessionId != queue.runtime.owner.parent.sessionId ||
            head.owner.setupBinding() != queue.original.binding ||
            head.deviceId != queue.original.device.deviceId ||
            head.roomId != queue.original.device.roomId
    )
        return false
    val anchor = head.eventId ?: return history.window == null
    if (!validCollaborationEventId(anchor)) return false
    return history.window?.let {
        validCollaborationOriginalWindow(it, head.owner, head.deviceId, head.roomId, anchor)
    } == true
}

/** Replacement content and other senders cannot confirm an original, even with the same ID. */
fun collaborationSpaceOriginalHistoryReceipt(
    record: CollaborationMessageRecord,
    changes: List<CollaborationHistoryChange>,
): CollaborationMessageReceipt? {
    val state = record.state as? CollaborationMessageState.Staged ?: return null
    val origin = state.origin ?: return null
    val matching =
        changes.filterIsInstance<CollaborationHistoryChange.Text>().filter {
            it.sender == origin.sender && it.logicalId == record.intent.id
        }
    if (matching.isEmpty()) return null
    var original: CollaborationHistoryMessage? = null
    for (change in matching) {
        original = mergeCollaborationHistoryText(original, change) ?: return null
    }
    return recoverCollaborationOriginalReceipt(record, requireNotNull(original))
}
