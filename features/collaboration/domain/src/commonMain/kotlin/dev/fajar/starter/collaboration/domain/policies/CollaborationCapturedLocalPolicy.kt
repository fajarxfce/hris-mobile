package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.CollaborationLocalAccess
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.Session

/** The caller separately checks the retained company gate. Ordinary age does not erase facts. */
fun collaborationCapturedLocalAccess(
    access: CollaborationLocalAccess,
    session: Session?,
    audience: String,
): AppResult<CollaborationLocalAccess> {
    if (
        session == null ||
            session.id != access.sessionId ||
            session.user.id != access.accountId ||
            session.binding.audience != audience ||
            access.audience != audience ||
            access.sessionId.length !in 1..128 ||
            access.sessionId.any { it.code !in 33..126 } ||
            !validCollaborationMessageId(access.accountId) ||
            !validCollaborationMessageId(access.companyId)
    )
        return collaborationSetupFailure("client.session_changed", FailureKind.Unauthorized)
    return AppResult.Success(access)
}
