package dev.fajar.starter.leave.domain.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.leave.domain.policies.*
import dev.fajar.starter.leave.domain.repositories.LeavePreparationRepository
import dev.fajar.starter.workspace.domain.entities.CompanyModule
import dev.fajar.starter.workspace.domain.entities.CompanyPolicySnapshot
import dev.fajar.starter.workspace.domain.policies.admitCompanyOperation
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*

class LoadLeaveTypes(
    private val remote: LeavePreparationRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
) {
    suspend operator fun invoke(
        workspaceId: String,
        selection: LeaveTypeSelection,
    ): AppResult<LeavePage<LeaveTypeOption>> =
        withTimeoutOrNull(30.seconds) {
            val valid = validateLeaveTypeSelection(selection)
            if (valid is AppResult.Failed) return@withTimeoutOrNull valid
            val access =
                when (val result = currentAccess(workspaceId)) {
                    is AppResult.Failed -> return@withTimeoutOrNull result
                    is AppResult.Success -> result.value
                }
            val requestedAt = clock.now()
            val policyResponse = workspaces.clientPolicy(access.sessionId, access.companyId)
            currentCoroutineContext().ensureActive()
            val ownership = validateLeaveContinuation(access, currentAccess(workspaceId))
            if (ownership is AppResult.Failed) return@withTimeoutOrNull ownership
            val policy =
                when (policyResponse) {
                    is AppResult.Failed -> return@withTimeoutOrNull policyResponse
                    is AppResult.Success -> CompanyPolicySnapshot(policyResponse.value, requestedAt)
                }
            val available = admitCompanyOperation(policy, build, CompanyModule.LEAVE, clock.now())
            if (available is AppResult.Failed) return@withTimeoutOrNull available
            val response = remote.types(access, selection)
            currentCoroutineContext().ensureActive()
            val publication = validateLeaveContinuation(access, currentAccess(workspaceId))
            if (publication is AppResult.Failed) return@withTimeoutOrNull publication
            val stillAvailable =
                admitCompanyOperation(policy, build, CompanyModule.LEAVE, clock.now())
            if (stillAvailable is AppResult.Failed) return@withTimeoutOrNull stillAvailable
            when (response) {
                is AppResult.Failed -> response
                is AppResult.Success ->
                    AppResult.Success(
                        response.value.copy(items = response.value.items.filter { it.active })
                    )
            }
        } ?: leaveFailure("client.leave_timeout", FailureKind.Timeout)

    private suspend fun currentAccess(workspaceId: String): AppResult<LeaveAccess> {
        val session =
            when (val result = sessions.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        currentCoroutineContext().ensureActive()
        val workspace =
            if (session == null) null
            else
                when (val result = workspaces.current(session.id)) {
                    is AppResult.Failed -> return result
                    is AppResult.Success -> result.value
                }
        currentCoroutineContext().ensureActive()
        val latestSession =
            when (val result = sessions.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        currentCoroutineContext().ensureActive()
        return when (
            val result = admitLeave(workspaceId, workspace, latestSession, build, clock.now())
        ) {
            is AppResult.Failed -> result
            is AppResult.Success ->
                when (val allowed = admitSelfLeavePreparation(result.value)) {
                    is AppResult.Failed -> allowed
                    is AppResult.Success -> result
                }
        }
    }
}
