package dev.fajar.starter.collaboration.presentation.thread

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.validCollaborationCancellation
import dev.fajar.starter.collaboration.domain.policies.validCollaborationEditRecord
import dev.fajar.starter.common.result.*

/** Presentation only: no ACK, retirement, queue replay or durable history mutation. */
internal fun chatThreadRows(
    owner: CollaborationMessageOwner,
    conversationId: String,
    self: CollaborationSelfIdentity?,
    timelineDevice: CollaborationMessageDevice?,
    history: List<CollaborationHistoryMessage>,
    pending: List<CollaborationMessageRecord>,
    timeline: List<CollaborationTimelineItem>,
    includeUnmatched: Boolean = true,
    editOutcomes: List<CollaborationEditOutcome> = emptyList(),
    historyRoomId: String? = null,
): AppResult<List<ChatMessageRow>> {
    if (history.size > 500 || pending.size > 128 || timeline.size > 128)
        return invalidThreadProjection()
    if (pending.map { it.intent.id }.distinct().size != pending.size)
        return invalidThreadProjection()
    if (pending.any { it.intent.owner != owner || it.intent.conversationId != conversationId })
        return invalidThreadProjection()
    if (pending.any { it.state == CollaborationMessageState.Cancelled })
        return invalidThreadProjection()
    if (timeline.isNotEmpty() && timelineDevice == null) return invalidThreadProjection()
    val rows = mutableListOf<ChatMessageRow>()
    val consumed = mutableSetOf<String>()
    val events = mutableSetOf<String>()
    val edits = pending.filter { it.intent.editTarget != null }
    if (
        edits.any { !validCollaborationEditRecord(it) } ||
            editOutcomes.size > 128 ||
            editOutcomes.map { it.operationId }.distinct().size != editOutcomes.size ||
            editOutcomes.any { outcome -> edits.none { it.intent.id == outcome.operationId } }
    )
        return invalidThreadProjection()
    val pendingById = pending.filter { it.intent.editTarget == null }.associateBy { it.intent.id }
    for (edit in edits) {
        if (
            edit.intent.editTarget?.roomId != historyRoomId &&
                edit.intent.editTarget?.roomId != timelineDevice?.roomId
        )
            continue
        (edit.state as? CollaborationMessageState.Sent)?.eventId?.let(events::add)
        timeline
            .firstOrNull { matchesEditEcho(edit, timelineDevice, it) }
            ?.eventId
            ?.let(events::add)
    }
    for (message in history) {
        val candidate = message.logicalId?.let(pendingById::get)
        val matching =
            candidate?.takeIf { record ->
                val origin = record.state.origin
                origin != null &&
                    origin.sender == message.sender &&
                    origin.contentHash == message.contentHash &&
                    record.intent.id == message.logicalId &&
                    (message.redacted || record.intent.text == message.text)
            }
        if (matching != null) consumed += matching.intent.id
        val own = self?.matrixUserId == message.sender
        val logicalKey =
            message.logicalId?.takeIf { own && (candidate == null || matching != null) }
        val key =
            when {
                matching != null -> "outgoing:${matching.intent.id}"
                logicalKey != null -> "outgoing:$logicalKey"
                else -> "event:${message.primaryEventId}"
            }
        events += message.eventIds
        val live =
            timeline.firstOrNull { it.eventId in message.eventIds && it.sender == message.sender }
        val originalRow = storedMessageRow(key, message, matching, self, live)
        rows +=
            projectChatEdits(
                originalRow,
                message,
                historyRoomId,
                edits,
                editOutcomes,
                timelineDevice,
                timeline,
            )
    }
    for (record in pending) {
        if (!includeUnmatched || record.intent.editTarget != null || record.intent.id in consumed)
            continue
        val native = timeline.firstOrNull { matchesOwnedEcho(record, timelineDevice, it) }
        native?.eventId?.let(events::add)
        val sent =
            record.state is CollaborationMessageState.Sent ||
                native?.delivery == CollaborationMessageDelivery.SENT
        rows += pendingRow(record, native, sent)
    }
    for (item in timeline) {
        // SDK local echoes may already have an event ID after PUT succeeds, while their
        // JSON/transaction metadata is still absent. Durable commands supply those rows;
        // event ID presence alone must not create a second outgoing bubble.
        if (item.localEcho) continue
        val eventId = item.eventId ?: continue
        if (!includeUnmatched || eventId in events || item.edited) continue
        rows += nativeRow(eventId, item, self)
        events += eventId
    }
    if (rows.map { it.key }.distinct().size != rows.size) return invalidThreadProjection()
    return AppResult.Success(
        // Pending outgoing rows follow confirmed history even when the phone clock is behind
        // the server. Their local creation order and keys survive each confirmed prefix.
        // Keep the actual timestamps and delivery evidence unchanged.
        rows.sortedWith(
            compareByDescending<ChatMessageRow> {
                    it.localId != null && it.delivery != CollaborationMessageDelivery.SENT
                }
                .thenByDescending { it.timestamp }
                .thenBy { it.key }
        )
    )
}

private fun storedMessageRow(
    key: String,
    message: CollaborationHistoryMessage,
    matching: CollaborationMessageRecord?,
    self: CollaborationSelfIdentity?,
    live: CollaborationTimelineItem?,
): ChatMessageRow {
    val own = self?.matrixUserId == message.sender
    val kind =
        when {
            message.redacted || live?.kind == CollaborationMessageKind.REDACTED ->
                CollaborationMessageKind.REDACTED
            else -> CollaborationMessageKind.TEXT
        }
    return ChatMessageRow(
        key,
        message.primaryEventId,
        matching?.intent?.id,
        message.timestamp,
        if (matching != null || own) ChatMessageOwnership.SELF
        else if (self == null) ChatMessageOwnership.UNKNOWN else ChatMessageOwnership.COLLEAGUE,
        kind,
        if (kind == CollaborationMessageKind.REDACTED) null
        else message.latestEdit?.edit?.text ?: message.text,
        CollaborationMessageDelivery.SENT,
        kind == CollaborationMessageKind.TEXT && message.latestEdit != null,
        reference =
            CollaborationMessageReference(
                message.sender,
                message.primaryEventId,
                message.logicalId,
            ),
        visibilityKnown = true,
        forwarded =
            kind == CollaborationMessageKind.TEXT &&
                (message.forwarded || matching?.intent?.forwarded == true),
        replyToEventId =
            if (kind == CollaborationMessageKind.TEXT)
                (message.replyToEventId ?: matching?.intent?.replyToEventId)
            else null,
    )
}

private fun invalidThreadProjection() =
    AppResult.Failed(
        Failure(
            FailureKind.Unavailable,
            "Refresh this conversation.",
            code = "client.collaboration_thread_changed",
        )
    )

private fun matchesOwnedEcho(
    record: CollaborationMessageRecord,
    source: CollaborationMessageDevice?,
    item: CollaborationTimelineItem,
): Boolean {
    val state = record.state
    val origin = state.origin ?: return false
    val transaction =
        when (state) {
            is CollaborationMessageState.Staged -> state.transactionId
            is CollaborationMessageState.Sent -> state.transactionId
            else -> return false
        }
    val device =
        when (state) {
            is CollaborationMessageState.Staged -> state.device
            is CollaborationMessageState.Sent -> state.device
        }
    val matchedTransport =
        item.transactionId == transaction ||
            (item.transactionId == null &&
                state is CollaborationMessageState.Sent &&
                item.eventId == state.eventId)
    return device == source &&
        item.own &&
        item.sender == origin.sender &&
        matchedTransport &&
        item.logicalId == record.intent.id
}

private fun nativeRow(
    eventId: String,
    item: CollaborationTimelineItem,
    self: CollaborationSelfIdentity?,
) =
    ChatMessageRow(
        "event:$eventId",
        eventId,
        null,
        item.timestamp,
        when {
            self?.matrixUserId == item.sender -> ChatMessageOwnership.SELF
            self == null -> ChatMessageOwnership.UNKNOWN
            else -> ChatMessageOwnership.COLLEAGUE
        },
        item.kind,
        item.body,
        item.delivery,
        item.edited,
        reference = CollaborationMessageReference(item.sender, eventId, item.logicalId),
        forwarded = item.kind == CollaborationMessageKind.TEXT && item.forwarded,
        replyToEventId =
            if (item.kind == CollaborationMessageKind.TEXT) item.replyToEventId else null,
    )

private fun pendingRow(
    record: CollaborationMessageRecord,
    native: CollaborationTimelineItem?,
    sent: Boolean,
) =
    ChatMessageRow(
        "outgoing:${record.intent.id}",
        (record.state as? CollaborationMessageState.Sent)?.eventId ?: native?.eventId,
        record.intent.id,
        record.intent.createdAt.toEpochMilliseconds(),
        ChatMessageOwnership.SELF,
        CollaborationMessageKind.TEXT,
        record.intent.text,
        if (sent) CollaborationMessageDelivery.SENT
        else native?.delivery ?: CollaborationMessageDelivery.PENDING,
        canCancel = !sent && native == null && validCollaborationCancellation(record),
        replyToEventId = record.intent.replyToEventId,
        forwarded = record.intent.forwarded,
        reference =
            if (sent) {
                val sender = record.state.origin?.sender
                val event =
                    (record.state as? CollaborationMessageState.Sent)?.eventId ?: native?.eventId
                if (sender != null && event != null)
                    CollaborationMessageReference(sender, event, record.intent.id)
                else null
            } else null,
    )
