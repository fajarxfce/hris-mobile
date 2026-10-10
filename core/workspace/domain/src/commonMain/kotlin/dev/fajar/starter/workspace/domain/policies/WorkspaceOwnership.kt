package dev.fajar.starter.workspace.domain.policies

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.Session
import dev.fajar.starter.workspace.domain.entities.Workspace

/** Pure admission check; use cases acquire the current repository snapshots. */
fun validateWorkspaceOwnership(
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
        session == null ||
            workspace.sessionId != session.id ||
            workspace.audience != session.binding.audience ->
            AppResult.Failed(
                Failure(FailureKind.Unauthorized, "Sign in again.", code = "client.session_changed")
            )
        else -> AppResult.Success(Unit)
    }
