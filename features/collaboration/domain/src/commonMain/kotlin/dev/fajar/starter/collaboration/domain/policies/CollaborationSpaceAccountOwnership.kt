package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.CollaborationSpaceAccountAccess
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.Session

fun validCollaborationSpaceAccountAccess(access: CollaborationSpaceAccountAccess): Boolean =
    access.sessionId.length in 1..128 &&
        access.sessionId.all { it.code in 33..126 } &&
        access.audience.length in 1..2048 &&
        access.audience.all { it.code in 33..126 } &&
        validCollaborationMessageId(access.accountId)

/** Local account ownership, independent of native age, platform and selected HR company. */
fun localCollaborationSpaceAccountAccess(
    expected: CollaborationSpaceAccountAccess,
    session: Session?,
    audience: String,
): AppResult<CollaborationSpaceAccountAccess> =
    if (
        !validCollaborationSpaceAccountAccess(expected) ||
            session == null ||
            session.id != expected.sessionId ||
            session.user.id != expected.accountId ||
            session.binding.audience != audience ||
            expected.audience != audience
    )
        collaborationRecoveryFailure("client.session_changed", FailureKind.Unauthorized)
    else AppResult.Success(expected)
