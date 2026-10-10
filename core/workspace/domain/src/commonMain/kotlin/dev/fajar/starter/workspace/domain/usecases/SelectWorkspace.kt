@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.workspace.domain.usecases

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.*
import dev.fajar.starter.workspace.domain.entities.Workspace
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.uuid.Uuid
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Selection rechecks membership and MFA before admitting a new company scope. */
class SelectWorkspace(
    private val sessions: SessionRepository,
    private val accounts: AccountRepository,
    private val workspaces: WorkspaceRepository,
) {
    suspend operator fun invoke(sessionId: String, companyId: String): AppResult<Workspace> {
        val session =
            when (val result = sessions.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        if (session?.id != sessionId)
            return AppResult.Failed(
                Failure(FailureKind.Unauthorized, "Sign in again.", code = "client.session_changed")
            )
        val previous =
            when (val result = workspaces.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        val account =
            when (val result = accounts.load(sessionId)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        if (account.user.id != session.user.id)
            return AppResult.Failed(
                Failure(FailureKind.Unauthorized, "Sign in again.", code = "client.session_changed")
            )
        if (account.assurance.required && !account.assurance.verified)
            return AppResult.Failed(
                Failure(FailureKind.AccessDenied, "Verify your account.", code = "mfa_required")
            )
        val company =
            account.companies.firstOrNull { it.id == companyId }
                ?: return AppResult.Failed(
                    Failure(
                        FailureKind.AccessDenied,
                        "Company access is unavailable.",
                        code = "company_access_denied",
                    )
                )
        val permissions =
            when (val result = workspaces.permissions(sessionId, companyId)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        val current =
            when (val result = sessions.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        if (current?.id != sessionId || current.binding != session.binding)
            return AppResult.Failed(
                Failure(FailureKind.Unauthorized, "Sign in again.", code = "client.session_changed")
            )
        currentCoroutineContext().ensureActive()
        val selected =
            Workspace(
                Uuid.random().toString(),
                sessionId,
                session.binding.audience,
                company,
                permissions,
            )
        return when (val result = workspaces.compareAndSet(previous, selected)) {
            is AppResult.Failed -> result
            is AppResult.Success ->
                if (result.value) AppResult.Success(selected)
                else
                    AppResult.Failed(
                        Failure(
                            FailureKind.Conflict,
                            "The company selection changed.",
                            code = "client.workspace_changed",
                        )
                    )
        }
    }
}
