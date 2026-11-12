package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

/** Capture bounded repository values before any suspending publication check. */
fun collaborationSpaceThreadHistory(
    access: CollaborationSpaceLocalAccess,
    conversationId: String,
    location: CollaborationSpaceHistoryLocation,
    window: CollaborationHistoryWindow,
    page: CollaborationHistoryPage,
): AppResult<CollaborationSpaceThreadHistory> {
    if (
        !validCollaborationHistoryWindow(window) ||
            page.messages.size > window.limit ||
            page.messages.any { !validCollaborationHistoryMessage(it) } ||
            !validCollaborationHistoryWindow(CollaborationHistoryWindow(after = page.next)) ||
            !validCollaborationHistoryWindow(CollaborationHistoryWindow(before = page.previous)) ||
            (page.roomId != location.roomId &&
                (page.roomId != null ||
                    page.messages.isNotEmpty() ||
                    page.next != null ||
                    page.previous != null ||
                    page.revision != null))
    )
        return collaborationRecoveryFailure(
            "client.collaboration_invalid_response",
            FailureKind.Unexpected,
        )
    return AppResult.Success(
        CollaborationSpaceThreadHistory(
            access,
            conversationId,
            location,
            page.copy(messages = page.messages.map { it.copy(eventIds = it.eventIds.toList()) }),
        )
    )
}
