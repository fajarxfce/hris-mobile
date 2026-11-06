package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

fun validCollaborationHistoryWindow(window: CollaborationHistoryWindow): Boolean =
    window.limit in 1..150 &&
        window.overlap in 0..50 &&
        window.overlap < window.limit &&
        (window.after == null || window.before == null) &&
        listOfNotNull(window.after, window.before).all(collaborationHistoryCursor::matches)

private val collaborationHistoryCursor = Regex("[0-9]{16}/m-[0-9a-f]{64}")

fun collaborationThreadContext(
    owner: CollaborationOwner,
    conversationId: String,
    summary: CollaborationDirectSummary?,
    self: CollaborationSelfIdentity?,
): AppResult<CollaborationThreadContext> {
    if (summary == null) return unavailableThreadContext()
    val direct = summary.conversation
    val peer =
        when (owner.accountId) {
            direct.firstAccountId -> direct.secondAccountId
            direct.secondAccountId -> direct.firstAccountId
            else -> null
        }
    if (
        direct.id != conversationId ||
            direct.companyId != owner.companyId ||
            peer == null ||
            peer == owner.accountId ||
            summary.peer.accountId != peer ||
            (self != null && !validCollaborationHistorySender(self.matrixUserId))
    )
        return unavailableThreadContext()
    return AppResult.Success(CollaborationThreadContext(summary, self))
}

private fun unavailableThreadContext() =
    collaborationSetupFailure(
        "client.collaboration_conversation_unavailable",
        FailureKind.Unavailable,
    )

/** Copies a bounded local result without acquiring authority or interpreting transport state. */
fun collaborationThreadSnapshot(
    access: CollaborationLocalAccess,
    conversationId: String,
    window: CollaborationHistoryWindow,
    context: CollaborationThreadContext,
    history: CollaborationHistoryPage,
    commands: List<CollaborationMessageRecord>,
): AppResult<CollaborationThreadSnapshot> {
    if (history.messages.size > window.limit || commands.size > 128)
        return collaborationSetupFailure("client.collaboration_thread_changed")
    val records = commands.toList()
    if (
        records.map { it.intent.id }.distinct().size != records.size ||
            records.any {
                it.intent.owner != access.messageOwner() ||
                    it.intent.conversationId != conversationId
            }
    )
        return collaborationSetupFailure("client.collaboration_thread_changed")
    return AppResult.Success(
        CollaborationThreadSnapshot(
            context,
            history.copy(messages = history.messages.toList()),
            records,
        )
    )
}
