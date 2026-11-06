package dev.fajar.starter.collaboration.presentation.thread

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.CollaborationMessageEditWindowMillis
import dev.fajar.starter.collaboration.domain.policies.matchesCollaborationEditTarget

/** Provider/local edit echoes never become independent bubbles or original receipt identities. */
internal fun matchesEditEcho(
    record: CollaborationMessageRecord,
    source: CollaborationMessageDevice?,
    item: CollaborationTimelineItem,
): Boolean {
    val target = record.intent.editTarget ?: return false
    val transaction =
        when (val state = record.state) {
            is CollaborationMessageState.Staged -> state.transactionId
            is CollaborationMessageState.Sent -> state.transactionId
            else -> return false
        }
    val device =
        when (val state = record.state) {
            is CollaborationMessageState.Staged -> state.device
            is CollaborationMessageState.Sent -> state.device
            else -> return false
        }
    return device == source &&
        item.own &&
        item.sender == target.sender &&
        item.transactionId == transaction &&
        item.eventId != target.eventId
}

internal fun projectChatEdits(
    row: ChatMessageRow,
    original: CollaborationHistoryMessage,
    roomId: String?,
    edits: List<CollaborationMessageRecord>,
    outcomes: List<CollaborationEditOutcome>,
    source: CollaborationMessageDevice?,
    timeline: List<CollaborationTimelineItem>,
): ChatMessageRow {
    if (row.ownership != ChatMessageOwnership.SELF || row.kind != CollaborationMessageKind.TEXT)
        return row
    val matching =
        edits.filter {
            val target = requireNotNull(it.intent.editTarget)
            target.roomId == roomId && matchesCollaborationEditTarget(original, target)
        }
    val states = outcomes.associate { it.operationId to it.kind }
    val newest =
        matching.maxWithOrNull(
            compareBy<CollaborationMessageRecord> { it.intent.createdAt }.thenBy { it.intent.id }
        )
    val pending =
        newest?.takeIf { states[it.intent.id] == CollaborationEditOutcomeKind.AWAITING_PROVIDER }
    val failed =
        matching
            .filter { states[it.intent.id] == CollaborationEditOutcomeKind.NOT_APPLIED }
            .sortedWith(
                compareBy<CollaborationMessageRecord> { it.intent.createdAt }
                    .thenBy { it.intent.id }
            )
    val local = pending?.let { edit -> timeline.firstOrNull { matchesEditEcho(edit, source, it) } }
    return row.copy(
        text = pending?.intent?.text ?: row.text,
        edited = row.edited || pending != null,
        editDelivery =
            if (pending == null) null
            else if (local?.delivery == CollaborationMessageDelivery.FAILED)
                CollaborationMessageDelivery.FAILED
            else CollaborationMessageDelivery.PENDING,
        failedEditIds = failed.map { it.intent.id }.sorted(),
        failedEditText = failed.takeIf { it.isNotEmpty() }?.joinToString("\n\n") { it.intent.text },
        editEligibleUntil =
            (original.firstAcceptedAt ?: original.timestamp) + CollaborationMessageEditWindowMillis,
    )
}
