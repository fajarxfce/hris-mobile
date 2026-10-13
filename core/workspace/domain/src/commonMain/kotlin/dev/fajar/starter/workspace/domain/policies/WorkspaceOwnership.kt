package dev.fajar.starter.workspace.domain.policies

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.Session
import dev.fajar.starter.workspace.domain.entities.Workspace
import kotlin.time.Instant

/** Local ownership survives stale authorization. It never authorizes an API operation. */
fun validateLocalWorkspaceOwnership(
    expectedId: String,
    workspace: Workspace?,
    session: Session?,
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
        else -> AppResult.Success(Unit)
    }

/** Operations that need recent authority must acquire it separately from local reads. */
fun validateWorkspaceOwnership(
    expectedId: String,
    workspace: Workspace?,
    session: Session?,
    now: Instant,
): AppResult<Unit> {
    val ownership = validateLocalWorkspaceOwnership(expectedId, workspace, session)
    if (ownership is AppResult.Failed) return ownership
    requireNotNull(workspace)
    requireNotNull(session)
    return when {
        session.binding.expiresAtEpochMillis <= now.toEpochMilliseconds() ->
            workspaceFailure("client.authentication_required", FailureKind.Unavailable)
        workspace.admission.checkedAt > now ||
            workspace.admission.expiresAt <= now ||
            workspace.admission.expiresAt >
                workspace.admission.checkedAt + WORKSPACE_AUTHORIZATION_PERIOD ||
            workspace.admission.expiresAt.toEpochMilliseconds() >
                session.binding.expiresAtEpochMillis -> workspaceFailure("client.workspace_expired")
        else -> AppResult.Success(Unit)
    }
}
