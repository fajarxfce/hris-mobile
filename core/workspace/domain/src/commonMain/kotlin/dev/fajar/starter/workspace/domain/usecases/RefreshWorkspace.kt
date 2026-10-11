@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.workspace.domain.usecases

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.*
import dev.fajar.starter.sync.domain.*
import dev.fajar.starter.workspace.domain.entities.CompanyPolicySnapshot
import dev.fajar.starter.workspace.domain.entities.Workspace
import dev.fajar.starter.workspace.domain.policies.*
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Rechecks live company authority without renewing a cached admission after transport failure. */
class RefreshWorkspace(
    private val sessions: SessionRepository,
    private val accounts: AccountRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock = Clock.System,
) : SyncTask {
    override val key = "hris-workspace-access"
    private val execution = Mutex()

    override suspend fun invoke(): SyncResult =
        execution.withLock {
            val result =
                withTimeoutOrNull(30.seconds) { refresh() }
                    ?: return@withLock SyncResult.Retry(
                        Failure(FailureKind.Timeout, "Company access timed out.")
                    )
            when (result) {
                is AppResult.Success -> SyncResult.Complete
                is AppResult.Failed -> syncFailure(result.failure)
            }
        }

    private suspend fun refresh(): AppResult<Unit> {
        val startedAt = clock.now()
        val session =
            when (val result = sessions.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value ?: return AppResult.Success(Unit)
            }
        val previous =
            when (val result = workspaces.current(session.id)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value ?: return AppResult.Success(Unit)
            }
        if (!belongsToWorkspaceSession(previous, session))
            return invalidate(
                previous,
                workspaceFailure("client.session_changed", FailureKind.Unauthorized).failure,
            )
        if (session.binding.expiresAtEpochMillis <= startedAt.toEpochMilliseconds())
            return invalidate(
                previous,
                workspaceFailure("session_revoked", FailureKind.Unauthorized).failure,
            )
        val account =
            when (val result = accounts.load(session.id)) {
                is AppResult.Failed -> return handleRejection(previous, result.failure)
                is AppResult.Success -> result.value
            }
        currentCoroutineContext().ensureActive()
        if (account.user.id != session.user.id)
            return invalidate(
                previous,
                workspaceFailure("client.session_changed", FailureKind.Unauthorized).failure,
            )
        val company =
            account.companies.singleOrNull { it.id == previous.company.id }
                ?: return invalidate(previous, workspaceFailure("company_access_denied").failure)
        val eligible = workspaceAdmission(session, account.assurance, startedAt, clock.now())
        if (eligible is AppResult.Failed) return invalidate(previous, eligible.failure)
        val permissions =
            when (val result = workspaces.permissions(session.id, company.id)) {
                is AppResult.Failed -> return handleRejection(previous, result.failure)
                is AppResult.Success -> result.value
            }
        val policyRequestedAt = clock.now()
        val policy =
            when (val result = workspaces.clientPolicy(session.id, company.id)) {
                is AppResult.Failed -> return handleRejection(previous, result.failure)
                is AppResult.Success -> CompanyPolicySnapshot(result.value, policyRequestedAt)
            }
        currentCoroutineContext().ensureActive()
        val currentSession =
            when (val result = sessions.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        if (
            currentSession?.id != session.id ||
                currentSession.binding != session.binding ||
                currentSession.user.id != session.user.id
        )
            return workspaceFailure("client.session_changed", FailureKind.Unauthorized)
        val admission =
            when (
                val result = workspaceAdmission(session, account.assurance, startedAt, clock.now())
            ) {
                is AppResult.Failed -> return invalidate(previous, result.failure)
                is AppResult.Success -> result.value
            }
        val updated =
            previous.copy(
                id =
                    if (previous.company == company && previous.permissions == permissions)
                        previous.id
                    else Uuid.random().toString(),
                company = company,
                permissions = permissions,
                admission = admission,
                policy = policy,
            )
        return when (val saved = workspaces.compareAndSet(session.id, previous, updated)) {
            is AppResult.Failed -> saved
            is AppResult.Success ->
                if (saved.value) AppResult.Success(Unit)
                else workspaceFailure("client.workspace_changed", FailureKind.Conflict)
        }
    }

    private suspend fun handleRejection(workspace: Workspace, failure: Failure): AppResult<Unit> =
        if (failure.kind == FailureKind.Unauthorized || failure.code in workspaceAccessLossCodes)
            invalidate(workspace, failure)
        else AppResult.Failed(failure)

    private suspend fun invalidate(workspace: Workspace, failure: Failure): AppResult<Unit> {
        currentCoroutineContext().ensureActive()
        return when (val cleared = workspaces.compareAndSet(workspace.sessionId, workspace, null)) {
            is AppResult.Failed -> cleared
            is AppResult.Success -> AppResult.Failed(failure)
        }
    }
}
