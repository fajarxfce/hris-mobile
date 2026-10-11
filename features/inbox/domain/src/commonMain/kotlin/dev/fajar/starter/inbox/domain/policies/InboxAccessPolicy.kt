package dev.fajar.starter.inbox.domain.policies

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.Session
import dev.fajar.starter.inbox.domain.entities.*
import dev.fajar.starter.workspace.domain.entities.Workspace
import dev.fajar.starter.workspace.domain.policies.validateWorkspaceOwnership
import kotlin.time.Instant

fun admitInbox(
    workspaceId: String,
    workspace: Workspace?,
    session: Session?,
    now: Instant,
): AppResult<InboxAccess> {
    val ownership = validateWorkspaceOwnership(workspaceId, workspace, session)
    if (ownership is AppResult.Failed) return ownership
    requireNotNull(workspace)
    requireNotNull(session)
    if (session.binding.expiresAtEpochMillis <= now.toEpochMilliseconds())
        return AppResult.Failed(
            Failure(FailureKind.Unauthorized, "Sign in again.", code = "session_revoked")
        )
    if ("announcements.read" !in workspace.permissions)
        return AppResult.Failed(
            Failure(
                FailureKind.AccessDenied,
                "Inbox access is unavailable.",
                code = "sync_access_denied",
            )
        )
    return AppResult.Success(
        InboxAccess(
            session.id,
            session.user.id,
            session.binding.audience,
            workspace.company.id,
            workspace.permissions.toSet(),
            Instant.fromEpochMilliseconds(session.binding.expiresAtEpochMillis),
        )
    )
}

fun validateInboxCache(
    access: InboxAccess,
    checkpoint: InboxCheckpoint?,
    now: Instant,
): AppResult<Unit> =
    when {
        checkpoint == null -> AppResult.Success(Unit)
        checkpoint.access != access ->
            AppResult.Failed(
                Failure(
                    FailureKind.AccessDenied,
                    "Refresh the inbox.",
                    code = "client.inbox_scope_changed",
                )
            )
        checkpoint.expiresAt <= now ->
            AppResult.Failed(
                Failure(
                    FailureKind.AccessDenied,
                    "Connect to refresh the inbox.",
                    code = "client.inbox_cache_expired",
                )
            )
        else -> AppResult.Success(Unit)
    }
