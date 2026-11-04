package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.CollaborationSpaceLocalAccess
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.Session

/** Local request ownership is independent of remote native lifetime and HR company selection. */
fun localCollaborationSpaceAccess(
    expected: CollaborationSpaceLocalAccess,
    session: Session?,
    audience: String,
): AppResult<CollaborationSpaceLocalAccess> {
    if (
        session == null ||
            session.id != expected.sessionId ||
            session.binding.audience != audience ||
            expected.audience != audience ||
            session.user.id != expected.accountId ||
            expected.sessionId.length !in 1..128 ||
            expected.sessionId.any { it.code !in 33..126 } ||
            expected.audience.length !in 1..2048 ||
            expected.audience.any { it.code !in 33..126 } ||
            !validCollaborationMessageId(expected.accountId) ||
            !validCollaborationMessageId(expected.spaceId)
    )
        return collaborationRecoveryFailure("client.session_changed", FailureKind.Unauthorized)
    return AppResult.Success(expected)
}
