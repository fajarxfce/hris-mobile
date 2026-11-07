package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

/** One immutable account snapshot, shared by the visible previews; never starts delivery. */
fun collaborationInboxCommands(
    access: CollaborationLocalAccess,
    commands: List<CollaborationMessageRecord>,
): AppResult<List<CollaborationMessageRecord>> {
    if (commands.size > 128) return invalidInboxPreview()
    val owned = commands.toList()
    if (
        owned.map { it.intent.id }.distinct().size != owned.size ||
            owned.any { it.intent.owner != access.messageOwner() }
    )
        return invalidInboxPreview()
    return AppResult.Success(
        owned.map { record ->
            val state = record.state
            if (state is CollaborationMessageState.Staging)
                record.copy(state = state.copy(before = state.before.toSet()))
            else record
        }
    )
}

/** Drafts and original send intents are local facts, independent of native ACK or token age. */
fun collaborationInboxPreview(
    conversationId: String,
    history: CollaborationHistoryPage,
    draft: CollaborationDraft?,
    commands: List<CollaborationMessageRecord>,
): AppResult<CollaborationInboxPreview> {
    if (
        history.messages.size > 1 ||
            (history.messages.isNotEmpty() && history.roomId == null) ||
            (draft != null && draft.conversationId != conversationId)
    )
        return invalidInboxPreview()
    val message = history.messages.singleOrNull()?.let { it.copy(eventIds = it.eventIds.toList()) }
    val sends =
        commands.filter {
            it.intent.conversationId == conversationId &&
                it.intent.editTarget == null &&
                it.state != CollaborationMessageState.Cancelled
        }
    val outgoing =
        sends
            .sortedWith(
                compareByDescending<CollaborationMessageRecord> { it.intent.createdAt }
                    .thenBy { it.intent.id }
            )
            .firstOrNull()
            ?.takeUnless { representedInInboxHistory(it, message, history.roomId) }
            ?.takeIf {
                it.state !is CollaborationMessageState.Sent ||
                    message == null ||
                    it.intent.createdAt.toEpochMilliseconds() >= message.timestamp
            }
    // A frozen composer is already a send action. Do not show it again as an editable draft
    // after its exact command is visible (including before the composer clear commits).
    val remainingDraft =
        draft?.takeUnless { slot ->
            slot.submissionId != null &&
                sends.any {
                    it.intent.id == slot.submissionId &&
                        it.intent.text == slot.text &&
                        it.intent.replyToEventId == slot.replyToEventId
                }
        }
    return AppResult.Success(CollaborationInboxPreview(message, remainingDraft, outgoing))
}

private fun representedInInboxHistory(
    record: CollaborationMessageRecord,
    message: CollaborationHistoryMessage?,
    roomId: String?,
): Boolean {
    if (message == null) return false
    val state = record.state
    if (
        state is CollaborationMessageState.Sent &&
            state.device.roomId == roomId &&
            state.eventId in message.eventIds
    )
        return true
    val origin = state.origin ?: return false
    return record.intent.id == message.logicalId &&
        origin.sender == message.sender &&
        origin.contentHash == message.contentHash &&
        (message.redacted || record.intent.text == message.text)
}

private fun invalidInboxPreview() =
    collaborationSetupFailure("client.collaboration_history_changed", FailureKind.Storage)
