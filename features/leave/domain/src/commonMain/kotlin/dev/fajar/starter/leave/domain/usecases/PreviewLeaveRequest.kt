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

class PreviewLeaveRequest(
    private val remote: LeavePreparationRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
) {
    suspend operator fun invoke(
        workspaceId: String,
        requested: LeaveDraftSelection,
    ): AppResult<LeavePreparation> {
        if (requested.days.size !in 1..366) return leaveFailure("invalid_leave_request")
        val selection = requested.copy(days = requested.days.toList().sortedBy { it.date })
        return withTimeoutOrNull(30.seconds) {
            val valid = validateLeaveDraftSelection(selection)
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
            val response = remote.preview(access, selection)
            currentCoroutineContext().ensureActive()
            val current =
                when (val result = validateLeaveContinuation(access, currentAccess(workspaceId))) {
                    is AppResult.Failed -> return@withTimeoutOrNull result
                    is AppResult.Success -> result.value
                }
            val now = clock.now()
            val stillAvailable = admitCompanyOperation(policy, build, CompanyModule.LEAVE, now)
            if (stillAvailable is AppResult.Failed) return@withTimeoutOrNull stillAvailable
            val preview =
                when (response) {
                    is AppResult.Failed -> return@withTimeoutOrNull response
                    is AppResult.Success -> response.value
                }
            if (
                preview.employeeId != selection.employeeId ||
                    preview.policy.typeId != selection.typeId
            )
                return@withTimeoutOrNull leaveFailure(
                    "client.invalid_response",
                    FailureKind.Unexpected,
                )
            val maintenance =
                policy.policy.maintenance
                    ?.let { policy.requestedAt + (it.startsAt - policy.policy.serverTime) }
                    ?.takeIf { it > now }
            val policyDeadline =
                policy.requestedAt + (policy.policy.validUntil - policy.policy.serverTime)
            val expiresAt =
                minOf(
                    now + LEAVE_PREPARATION_PERIOD,
                    current.authorizationExpiresAt,
                    current.sessionExpiresAt,
                    policyDeadline,
                    maintenance ?: current.authorizationExpiresAt,
                )
            AppResult.Success(LeavePreparation(current, selection, preview, now, expiresAt))
        } ?: leaveFailure("client.leave_timeout", FailureKind.Timeout)
    }

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
