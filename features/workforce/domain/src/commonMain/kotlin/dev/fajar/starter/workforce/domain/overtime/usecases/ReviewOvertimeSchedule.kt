package dev.fajar.starter.workforce.domain.overtime.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workforce.domain.entities.WorkforceAccess
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.domain.overtime.policies.*
import dev.fajar.starter.workforce.domain.overtime.repositories.*
import dev.fajar.starter.workforce.domain.repositories.WorkforceRepository
import dev.fajar.starter.workspace.domain.entities.CompanyModule
import dev.fajar.starter.workspace.domain.entities.CompanyPolicySnapshot
import dev.fajar.starter.workspace.domain.policies.admitCompanyOperation
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*

class ReviewOvertimeSchedule(
    private val workforce: WorkforceRepository,
    private val remote: OvertimePlanningRepository,
    private val cache: OvertimeRequestReplicaRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
) {
    suspend operator fun invoke(
        workspaceId: String,
        selection: OvertimePlanningSelection,
    ): AppResult<OvertimePreparation> =
        withTimeoutOrNull(30.seconds) {
            val selected = validateOvertimePlanningSelection(selection)
            if (selected is AppResult.Failed) return@withTimeoutOrNull selected
            val access =
                when (val result = currentAccess(workspaceId)) {
                    is AppResult.Failed -> return@withTimeoutOrNull result
                    is AppResult.Success -> result.value
                }
            val admitted = readContinuation(workspaceId, access)
            if (admitted is AppResult.Failed) return@withTimeoutOrNull admitted
            val requestedAt = clock.now()
            val response = workspaces.clientPolicy(access.sessionId, access.companyId)
            currentCoroutineContext().ensureActive()
            val ownership = readContinuation(workspaceId, access)
            if (ownership is AppResult.Failed) return@withTimeoutOrNull ownership
            val policy =
                when (response) {
                    is AppResult.Failed -> return@withTimeoutOrNull reject(access, response)
                    is AppResult.Success -> CompanyPolicySnapshot(response.value, requestedAt)
                }
            val available =
                admitCompanyOperation(policy, build, CompanyModule.WORKFORCE, clock.now())
            if (available is AppResult.Failed) return@withTimeoutOrNull reject(access, available)
            val employments = workforce.selfEmployments(access, selection.employmentAfter)
            currentCoroutineContext().ensureActive()
            val directoryOwnership = readContinuation(workspaceId, access)
            if (directoryOwnership is AppResult.Failed) return@withTimeoutOrNull directoryOwnership
            val directory =
                when (employments) {
                    is AppResult.Failed -> return@withTimeoutOrNull reject(access, employments)
                    is AppResult.Success -> employments.value
                }
            if (directory.accountId != access.accountId || directory.companyId != access.companyId)
                return@withTimeoutOrNull overtimeFailure(
                    "client.invalid_response",
                    FailureKind.Unexpected,
                )
            if (directory.items.none { it.id == selection.employeeId })
                return@withTimeoutOrNull reject(
                    access,
                    overtimeFailure("employee_not_found", FailureKind.AccessDenied),
                )
            val dates = validateOvertimeWorkDate(selection.workDate, directory.asOf)
            if (dates is AppResult.Failed) return@withTimeoutOrNull dates
            val stillAvailable =
                admitCompanyOperation(policy, build, CompanyModule.WORKFORCE, clock.now())
            if (stillAvailable is AppResult.Failed)
                return@withTimeoutOrNull reject(access, stillAvailable)
            val acquired = remote.review(access, selection)
            currentCoroutineContext().ensureActive()
            val current =
                when (val result = readContinuation(workspaceId, access)) {
                    is AppResult.Failed -> return@withTimeoutOrNull result
                    is AppResult.Success -> result.value
                }
            val now = clock.now()
            val publication = admitCompanyOperation(policy, build, CompanyModule.WORKFORCE, now)
            if (publication is AppResult.Failed)
                return@withTimeoutOrNull reject(access, publication)
            val review =
                when (acquired) {
                    is AppResult.Failed -> return@withTimeoutOrNull reject(access, acquired)
                    is AppResult.Success -> acquired.value
                }
            val bounded = boundOvertimeAuthorization(current, policy, now)
            val expiry =
                minOf(
                    now + OVERTIME_PREPARATION_PERIOD,
                    bounded.authorizationExpiresAt,
                    bounded.sessionExpiresAt,
                    policy.requestedAt + (policy.policy.validUntil - policy.policy.serverTime),
                )
            val prepared = OvertimePreparation(bounded, selection, review, now, expiry)
            when (val valid = validateOvertimePreparation(prepared, current, now)) {
                is AppResult.Failed -> valid
                is AppResult.Success -> AppResult.Success(prepared)
            }
        } ?: overtimeFailure("client.overtime_timeout", FailureKind.Timeout)

    private suspend fun reject(
        access: WorkforceAccess,
        failure: AppResult.Failed,
    ): AppResult.Failed {
        if (overtimeFailureInvalidatesCache(failure.failure)) {
            withTimeoutOrNull(2.seconds) { cache.restrict(access, failure.failure) }
        }
        return failure
    }

    private suspend fun readContinuation(
        workspaceId: String,
        access: WorkforceAccess,
    ): AppResult<WorkforceAccess> {
        val current = validateOvertimeContinuation(access, currentAccess(workspaceId))
        if (current is AppResult.Failed) return current
        val admission = cache.admission(access)
        currentCoroutineContext().ensureActive()
        val owner = validateOvertimeContinuation(access, currentAccess(workspaceId))
        if (owner is AppResult.Failed) return owner
        return when (admission) {
            is AppResult.Failed -> admission
            is AppResult.Success -> admission.value.denial?.let { AppResult.Failed(it) } ?: owner
        }
    }

    private suspend fun currentAccess(workspaceId: String): AppResult<WorkforceAccess> {
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
        val latest =
            when (val result = sessions.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        currentCoroutineContext().ensureActive()
        return when (
            val result = admitOvertime(workspaceId, workspace, latest, build, clock.now())
        ) {
            is AppResult.Failed -> result
            is AppResult.Success ->
                when (val allowed = admitSelfOvertimeCommands(result.value)) {
                    is AppResult.Failed -> allowed
                    is AppResult.Success -> result
                }
        }
    }
}
