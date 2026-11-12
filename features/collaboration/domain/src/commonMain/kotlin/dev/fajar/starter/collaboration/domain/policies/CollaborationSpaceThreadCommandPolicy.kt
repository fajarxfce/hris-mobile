package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

/** Capture the canonical ledger before combining it with another suspending local read. */
fun collaborationSpaceThreadCommands(
    access: CollaborationSpaceLocalAccess,
    conversationId: String,
    records: List<CollaborationSpaceMessageRecord>,
): AppResult<List<CollaborationSpaceMessageRecord>> {
    val captured = collaborationSpaceMessageInventory(access, records)
    if (
        !validCollaborationSpaceConversationId(conversationId) ||
            captured == null ||
            captured.any { it.intent.conversationId != conversationId }
    )
        return collaborationRecoveryFailure(
            "client.collaboration_invalid_response",
            FailureKind.Unexpected,
        )
    return AppResult.Success(captured)
}

/** Original commands remain Company records; an organization read never rewrites their owner. */
fun collaborationSpaceOriginalThreadCommands(
    location: CollaborationSpaceHistoryLocation.Original,
    records: List<CollaborationMessageRecord>,
): AppResult<List<CollaborationMessageRecord>> {
    if (
        records.size > 128 ||
            records.map { it.intent.id }.distinct().size != records.size ||
            records.any {
                val intent = it.intent
                intent.owner != location.access.messageOwner() ||
                    intent.conversationId != location.conversationId ||
                    !validCollaborationMessageId(intent.id) ||
                    !validCollaborationText(intent.text) ||
                    intent.createdAt.toEpochMilliseconds() !in 0 until 253_402_300_800_000L ||
                    intent.replyToEventId?.let(::validCollaborationReplyEventId) == false ||
                    !validCollaborationEditRecord(it) ||
                    !validCollaborationMessageState(it.state) ||
                    it.revision !in 0..9_007_199_254_740_991L
            }
    )
        return collaborationRecoveryFailure(
            "client.collaboration_invalid_response",
            FailureKind.Unexpected,
        )
    return AppResult.Success(
        records
            .map { record ->
                val state = record.state
                if (state is CollaborationMessageState.Staging)
                    record.copy(state = state.copy(before = state.before.toSet()))
                else record
            }
            .sortedWith(compareBy({ it.intent.createdAt }, { it.intent.id }))
    )
}
