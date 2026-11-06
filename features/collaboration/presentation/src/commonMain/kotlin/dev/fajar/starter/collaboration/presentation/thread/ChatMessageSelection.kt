package dev.fajar.starter.collaboration.presentation.thread

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.validCollaborationReplyEventId

internal fun sameChatMessage(a: ChatMessageRow, b: ChatMessageRow): Boolean {
    if (a.key == b.key) return true
    val first = a.reference ?: return false
    val second = b.reference ?: return false
    return first.sender == second.sender &&
        (first.eventId == second.eventId ||
            (first.logicalId != null && first.logicalId == second.logicalId))
}

internal val ChatThreadState.canCopySelection: Boolean
    get() =
        selectedMessages.isNotEmpty() &&
            selectedMessages.all { it.kind == CollaborationMessageKind.TEXT && it.text != null }

internal val ChatThreadState.canEditSelection: Boolean
    get() =
        selectedMessages.singleOrNull()?.let {
            it.ownership == ChatMessageOwnership.SELF &&
                it.visibilityKnown &&
                it.kind == CollaborationMessageKind.TEXT &&
                it.text != null &&
                it.reference?.eventId?.let(::validCollaborationReplyEventId) == true &&
                it.delivery == CollaborationMessageDelivery.SENT &&
                it.editDelivery == null &&
                it.editEligibleUntil?.let { deadline ->
                    selectionTime >= it.timestamp && selectionTime < deadline
                } == true
        } == true && copy(selecting = false).canCompose

internal val ChatThreadState.canDismissFailedEdits: Boolean
    get() =
        !editAcknowledging &&
            selectedMessages.singleOrNull()?.let {
                it.ownership == ChatMessageOwnership.SELF &&
                    it.visibilityKnown &&
                    it.reference != null &&
                    it.failedEditIds.isNotEmpty()
            } == true

internal val ChatThreadState.canCopyFailedEdits: Boolean
    get() =
        selectedMessages.singleOrNull()?.let {
            it.ownership == ChatMessageOwnership.SELF &&
                it.visibilityKnown &&
                it.reference != null &&
                it.failedEditIds.isNotEmpty() &&
                it.failedEditText != null
        } == true

internal val ChatThreadState.canDeleteSelection: Boolean
    get() = selectedMessages.isNotEmpty() && selectedMessages.all { it.reference != null }

internal val ChatThreadState.canForwardSelection: Boolean
    get() =
        selectedMessages.isNotEmpty() &&
            selectedMessages.all {
                it.reference != null &&
                    it.kind == CollaborationMessageKind.TEXT &&
                    it.text != null &&
                    it.delivery == CollaborationMessageDelivery.SENT &&
                    !it.edited
            }

internal data class ChatVisibilityProjection(
    val access: CollaborationLocalAccess,
    val roomId: String?,
    val rows: List<ChatMessageRow>,
    val loading: Boolean,
    val hasOlder: Boolean,
    val hasNewer: Boolean,
    val browsingHistory: Boolean,
) {
    override fun toString() = "ChatVisibilityProjection([redacted])"
}

internal data class ChatDeletionSelection(
    val access: CollaborationLocalAccess,
    val roomId: String,
    val messages: List<CollaborationMessageReference>,
) {
    override fun toString() = "ChatDeletionSelection([redacted])"
}

internal val ChatThreadState.canReplySelection: Boolean
    get() =
        selectedMessages.singleOrNull()?.let {
            it.reference?.eventId?.let(::validCollaborationReplyEventId) == true &&
                it.kind == CollaborationMessageKind.TEXT &&
                it.text != null &&
                it.delivery == CollaborationMessageDelivery.SENT
        } == true && copy(selecting = false).canCompose

internal data class ChatQuoteRequest(
    val access: CollaborationLocalAccess,
    val roomId: String,
    val eventIds: List<String>,
) {
    override fun toString() = "ChatQuoteRequest([redacted])"
}
