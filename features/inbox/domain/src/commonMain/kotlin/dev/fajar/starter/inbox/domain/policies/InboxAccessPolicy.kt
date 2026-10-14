package dev.fajar.starter.inbox.domain.policies

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.Session
import dev.fajar.starter.inbox.domain.entities.*
import dev.fajar.starter.workspace.domain.entities.CompanyModule
import dev.fajar.starter.workspace.domain.entities.Workspace
import dev.fajar.starter.workspace.domain.policies.requireCompanyModule
import dev.fajar.starter.workspace.domain.policies.validateLocalWorkspaceOwnership
import kotlin.time.Instant

fun admitInbox(
    workspaceId: String,
    workspace: Workspace?,
    session: Session?,
): AppResult<InboxAccess> {
    val ownership = validateLocalWorkspaceOwnership(workspaceId, workspace, session)
    if (ownership is AppResult.Failed) return ownership
    requireNotNull(workspace)
    requireNotNull(session)
    val module = requireCompanyModule(workspace, CompanyModule.COMMUNICATIONS)
    if (module is AppResult.Failed) return module
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

fun validateInboxActionSnapshot(
    access: InboxAccess,
    checkpoint: InboxCheckpoint?,
    now: Instant,
): AppResult<Unit> =
    when {
        access.workspaceExpiresAt <= now ->
            AppResult.Failed(
                Failure(
                    FailureKind.Unavailable,
                    "Refresh company access before sending this action.",
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
        minOf(checkpoint.refreshAfter, access.workspaceExpiresAt) <= now ->
            AppResult.Failed(
                Failure(
                    FailureKind.Unavailable,
                    "Review the latest message before sending this action.",
                    code = "client.inbox_review_required",
                )
            )
        else -> AppResult.Success(Unit)
    }

/** Renewing a lease does not change the resource scope or require another bootstrap. */
fun sameInboxScope(first: InboxAccess, second: InboxAccess): Boolean =
    sameStoredInboxScope(first, second) &&
        first.sessionId == second.sessionId &&
        first.sessionExpiresAt == second.sessionExpiresAt

/** Credential rotation/replacement does not change who owns a published local projection. */
fun sameStoredInboxScope(first: InboxAccess, second: InboxAccess): Boolean =
    first.accountId == second.accountId &&
        first.audience == second.audience &&
        first.companyId == second.companyId &&
        first.permissions == second.permissions

/** Reading a saved message requires its current local owner, not an unexpired sync cursor. */
fun validateStoredInbox(access: InboxAccess, checkpoint: InboxCheckpoint?): AppResult<Unit> =
    if (checkpoint != null && !sameStoredInboxScope(checkpoint.access, access))
        inboxCommandFailure("client.inbox_scope_changed", FailureKind.AccessDenied)
    else AppResult.Success(Unit)

/** A freshness or credential-renewal failure is not a revocation of locally owned facts. */
fun inboxFailureInvalidatesCache(failure: Failure): Boolean =
    failure.kind in setOf(FailureKind.Unauthorized, FailureKind.AccessDenied) ||
        failure.code in
            setOf(
                "sync_scope_changed",
                "company_maintenance",
                "client_update_required",
                "client_version_required",
            )
