package dev.fajar.starter.inbox.domain.policies

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.Session
import dev.fajar.starter.inbox.domain.entities.*
import dev.fajar.starter.workspace.domain.entities.CompanyModule
import dev.fajar.starter.workspace.domain.entities.Workspace
import dev.fajar.starter.workspace.domain.policies.requireCompanyModule
import dev.fajar.starter.workspace.domain.policies.validateWorkspaceOwnership
import kotlin.time.Instant

fun admitInbox(
    workspaceId: String,
    workspace: Workspace?,
    session: Session?,
    now: Instant,
): AppResult<InboxAccess> {
    val ownership = validateWorkspaceOwnership(workspaceId, workspace, session, now)
    if (ownership is AppResult.Failed) return ownership
    requireNotNull(workspace)
    requireNotNull(session)
    val module = requireCompanyModule(workspace, CompanyModule.COMMUNICATIONS)
    if (module is AppResult.Failed) return module
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
            workspace.admission.expiresAt,
        )
    )
}

fun validateInboxCache(
    access: InboxAccess,
    checkpoint: InboxCheckpoint?,
    now: Instant,
): AppResult<Unit> =
    when {
        access.workspaceExpiresAt <= now ->
            AppResult.Failed(
                Failure(
                    FailureKind.AccessDenied,
                    "Select a company again.",
                    code = "client.workspace_expired",
                )
            )
        checkpoint == null -> AppResult.Success(Unit)
        !sameInboxScope(checkpoint.access, access) ->
            AppResult.Failed(
                Failure(
                    FailureKind.AccessDenied,
                    "Refresh the inbox.",
                    code = "client.inbox_scope_changed",
                )
            )
        minOf(checkpoint.expiresAt, access.workspaceExpiresAt) <= now ->
            AppResult.Failed(
                Failure(
                    FailureKind.AccessDenied,
                    "Connect to refresh the inbox.",
                    code = "client.inbox_cache_expired",
                )
            )
        else -> AppResult.Success(Unit)
    }

/** Renewing a lease does not change the resource scope or require another bootstrap. */
fun sameInboxScope(first: InboxAccess, second: InboxAccess): Boolean =
    first.sessionId == second.sessionId &&
        first.accountId == second.accountId &&
        first.audience == second.audience &&
        first.companyId == second.companyId &&
        first.permissions == second.permissions &&
        first.sessionExpiresAt == second.sessionExpiresAt
