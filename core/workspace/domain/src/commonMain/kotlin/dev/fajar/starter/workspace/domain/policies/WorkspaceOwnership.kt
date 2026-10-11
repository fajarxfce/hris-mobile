package dev.fajar.starter.workspace.domain.policies

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.Session
import dev.fajar.starter.workspace.domain.entities.Workspace
import kotlin.time.Instant

/** Pure admission check; use cases acquire the current repository snapshots. */
fun validateWorkspaceOwnership(
    expectedId: String,
    workspace: Workspace?,
    session: Session?,
    now: Instant,
): AppResult<Unit> =
    when {
        workspace?.id != expectedId ->
            AppResult.Failed(
                Failure(
                    FailureKind.AccessDenied,
                    "Select a company again.",
                    code = "client.workspace_changed",
                )
            )
        session == null || !belongsToWorkspaceSession(workspace, session) ->
            AppResult.Failed(
                Failure(FailureKind.Unauthorized, "Sign in again.", code = "client.session_changed")
            )
        session.binding.expiresAtEpochMillis <= now.toEpochMilliseconds() ->
            workspaceFailure("session_revoked", FailureKind.Unauthorized)
        workspace.admission.checkedAt > now ||
            workspace.admission.expiresAt <= now ||
            workspace.admission.expiresAt >
                workspace.admission.checkedAt + WORKSPACE_OFFLINE_PERIOD ||
            workspace.admission.expiresAt.toEpochMilliseconds() >
                session.binding.expiresAtEpochMillis -> workspaceFailure("client.workspace_expired")
        else -> AppResult.Success(Unit)
    }
