@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.workspace.domain.usecases

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.Session
import dev.fajar.starter.identity.domain.repositories.*
import dev.fajar.starter.workspace.domain.entities.CompanyPolicySnapshot
import dev.fajar.starter.workspace.domain.entities.Workspace
import dev.fajar.starter.workspace.domain.entities.WorkspaceSelectionOrigin
import dev.fajar.starter.workspace.domain.policies.*
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Selection rechecks membership and MFA before admitting a new company scope. */
class SelectWorkspace(
    private val sessions: SessionRepository,
    private val accounts: AccountRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock = Clock.System,
) {
    suspend operator fun invoke(
        sessionId: String,
        companyId: String,
        expectedOrigin: WorkspaceSelectionOrigin? = null,
    ): AppResult<Workspace> {
        currentCoroutineContext().ensureActive()
        val session =
            when (val result = sessions.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        currentCoroutineContext().ensureActive()
        if (session?.id != sessionId || expectedOrigin?.owner?.matches(session) == false)
            return sessionChanged()
        return select(session, companyId, expectedOrigin)
    }

    private suspend fun select(
        session: Session,
        companyId: String,
        expectedOrigin: WorkspaceSelectionOrigin?,
    ): AppResult<Workspace> {
        val sessionId = session.id
        val startedAt = clock.now()
        val previous =
            when (val result = workspaces.current(sessionId)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        continuation(session).let { if (it is AppResult.Failed) return it }
        if (expectedOrigin != null && previous?.id != expectedOrigin.workspaceId)
            return selectionChanged()
        val account =
            when (val result = accounts.load(sessionId)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        continuation(session).let { if (it is AppResult.Failed) return it }
        if (account.user.id != session.user.id) return sessionChanged()
        val eligible = workspaceAdmission(session, account.assurance, startedAt, clock.now())
        if (eligible is AppResult.Failed) return eligible
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
        continuation(session).let { if (it is AppResult.Failed) return it }
        val policyRequestedAt = clock.now()
        val policy =
            when (val result = workspaces.clientPolicy(sessionId, companyId)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> CompanyPolicySnapshot(result.value, policyRequestedAt)
            }
        continuation(session).let { if (it is AppResult.Failed) return it }
        val admission =
            when (
                val result = workspaceAdmission(session, account.assurance, startedAt, clock.now())
            ) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        val selected =
            Workspace(
                Uuid.random().toString(),
                sessionId,
                session.binding.audience,
                company,
                permissions,
                admission,
                policy,
            )
        val result = workspaces.compareAndSet(sessionId, previous, selected)
        continuation(session).let { if (it is AppResult.Failed) return it }
        return when (result) {
            is AppResult.Failed -> result
            is AppResult.Success ->
                if (result.value) AppResult.Success(selected) else selectionChanged()
        }
    }

    private suspend fun continuation(expected: Session): AppResult<Unit> {
        currentCoroutineContext().ensureActive()
        val found = sessions.current()
        currentCoroutineContext().ensureActive()
        if (found is AppResult.Failed) return found
        val current = (found as AppResult.Success).value
        return if (
            current?.id == expected.id &&
                current.user.id == expected.user.id &&
                current.binding == expected.binding
        )
            AppResult.Success(Unit)
        else sessionChanged()
    }

    private fun sessionChanged() =
        AppResult.Failed(
            Failure(FailureKind.Unauthorized, "Sign in again.", code = "client.session_changed")
        )

    private fun selectionChanged() =
        AppResult.Failed(
            Failure(
                FailureKind.Conflict,
                "The company selection changed.",
                code = "client.workspace_changed",
            )
        )
}
