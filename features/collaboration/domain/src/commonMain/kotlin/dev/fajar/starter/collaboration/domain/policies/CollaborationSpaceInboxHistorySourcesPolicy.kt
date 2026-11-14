package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

/** Detach a bounded inventory before admission can suspend; its continuation stays opaque. */
fun captureCollaborationSpaceInboxHistorySources(
    access: CollaborationSpaceLocalAccess,
    after: String?,
    limit: Int,
    page: CollaborationSpaceInboxHistorySourcePage,
): AppResult<CollaborationSpaceInboxHistorySourcePage> {
    val sources = page.sources.toList()
    if (
        limit !in 1..16 ||
            sources.size > limit ||
            sources.any { !validCollaborationSpaceInboxHistorySourceCheckpoint(access, it) } ||
            sources.zipWithNext().any { (a, b) ->
                a.progress.conversationId >= b.progress.conversationId
            } ||
            (page.next != null &&
                (page.next.length !in 1..4096 || page.next == after || sources.size != limit))
    )
        return collaborationRecoveryFailure(
            "client.collaboration_invalid_response",
            FailureKind.Unexpected,
        )
    return AppResult.Success(page.copy(sources = sources))
}
