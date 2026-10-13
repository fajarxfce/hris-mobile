package dev.fajar.starter.workspace.domain.policies

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.*
import dev.fajar.starter.workspace.domain.entities.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

val WORKSPACE_AUTHORIZATION_PERIOD = 1.hours

fun workspaceAdmission(
    session: Session,
    assurance: SessionAssurance,
    checkedAt: Instant,
    now: Instant,
): AppResult<WorkspaceAdmission> {
    val sessionEnd = Instant.fromEpochMilliseconds(session.binding.expiresAtEpochMillis)
    if (sessionEnd <= now)
        return workspaceFailure("client.authentication_required", FailureKind.Unavailable)
    val assuranceEnd =
        if (assurance.required) {
            val end = assurance.validUntilEpochMillis?.let(Instant::fromEpochMilliseconds)
            if (!assurance.verified || end == null || end <= now)
                return workspaceFailure("mfa_required")
            end
        } else sessionEnd
    val expiresAt = minOf(checkedAt + WORKSPACE_AUTHORIZATION_PERIOD, sessionEnd, assuranceEnd)
    if (checkedAt > now || expiresAt <= now)
        return workspaceFailure("client.workspace_expired", FailureKind.Unavailable)
    return AppResult.Success(
        WorkspaceAdmission(session.user.id, session.binding.serverId, checkedAt, expiresAt)
    )
}

fun belongsToWorkspaceSession(workspace: Workspace, session: Session): Boolean =
    workspace.sessionId == session.id &&
        workspace.audience == session.binding.audience &&
        workspace.admission.accountId == session.user.id &&
        workspace.admission.familyId == session.binding.serverId

fun workspaceFailure(code: String, kind: FailureKind = FailureKind.AccessDenied) =
    AppResult.Failed(Failure(kind, "Connect and select a company again.", code = code))

/** These authoritative responses invalidate a cached admission; transport failures do not. */
val workspaceAccessLossCodes =
    setOf(
        "session_revoked",
        "native_session_invalid",
        "company_access_denied",
        "company_not_found",
        "mfa_required",
        "mfa_setup_required",
        "mfa_verification_required",
        "recent_authentication_required",
    )
