@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.leave.domain.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.leave.domain.policies.*
import dev.fajar.starter.leave.domain.repositories.*
import dev.fajar.starter.workspace.domain.entities.CompanyModule
import dev.fajar.starter.workspace.domain.entities.CompanyPolicySnapshot
import dev.fajar.starter.workspace.domain.policies.admitCompanyOperation
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Owns one finite acquisition and atomic snapshot publication across all route owners. */
class RefreshLeave(
    private val remote: LeaveRepository,
    private val cache: LeaveCacheRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
) {
    private val execution = Mutex()

    suspend operator fun invoke(workspaceId: String, selection: LeaveSelection): AppResult<Unit> =
        execution.withLock {
            withTimeoutOrNull(30.seconds) { acquire(workspaceId, selection) }
                ?: leaveFailure("client.leave_timeout", FailureKind.Timeout)
        }

    private suspend fun acquire(workspaceId: String, selection: LeaveSelection): AppResult<Unit> {
        val valid = validateLeaveSelection(selection)
        if (valid is AppResult.Failed) return valid
        val access =
            when (val current = currentAccess(workspaceId)) {
                is AppResult.Failed -> return current
                is AppResult.Success -> current.value
            }
        val previousGeneration =
            when (val saved = cache.generation(access)) {
                is AppResult.Failed -> return saved
                is AppResult.Success -> saved.value
            }
        val requestedAt = clock.now()
        val policyResponse = workspaces.clientPolicy(access.sessionId, access.companyId)
        currentCoroutineContext().ensureActive()
        val policyOwnership = validateLeaveContinuation(access, currentAccess(workspaceId))
        if (policyOwnership is AppResult.Failed) return policyOwnership
        val policy =
            when (policyResponse) {
                is AppResult.Failed -> return reject(access, previousGeneration, policyResponse)
                is AppResult.Success -> CompanyPolicySnapshot(policyResponse.value, requestedAt)
            }
        val availability = admitCompanyOperation(policy, build, CompanyModule.LEAVE, clock.now())
        if (availability is AppResult.Failed)
            return reject(access, previousGeneration, availability)
        return loadOverview(workspaceId, selection, access, previousGeneration, policy)
    }

    private suspend fun loadOverview(
        workspaceId: String,
        selection: LeaveSelection,
        access: LeaveAccess,
        previousGeneration: String?,
        policy: CompanyPolicySnapshot,
    ): AppResult<Unit> {
        val directoryResponse = remote.selfEmployments(access, selection.employmentAfter)
        currentCoroutineContext().ensureActive()
        val directoryOwnership = validateLeaveContinuation(access, currentAccess(workspaceId))
        if (directoryOwnership is AppResult.Failed) return directoryOwnership
        val directory =
            when (directoryResponse) {
                is AppResult.Failed -> return reject(access, previousGeneration, directoryResponse)
                is AppResult.Success -> directoryResponse.value
            }
        if (directory.accountId != access.accountId || directory.companyId != access.companyId)
            return leaveFailure("client.invalid_response", FailureKind.Unexpected)
        val year = selection.year ?: directory.asOf.year
        if (year !in 1900..2200) return leaveFailure("invalid_leave_year")
        val employee =
            if (selection.employeeId == null)
                directory.items.firstOrNull { it.status == LeaveEmploymentStatus.ACTIVE }
                    ?: directory.items.firstOrNull()
            else directory.items.firstOrNull { it.id == selection.employeeId }
        if (selection.employeeId != null && employee == null)
            return reject(
                access,
                previousGeneration,
                leaveFailure("employee_not_found", FailureKind.AccessDenied),
            )
        val stillAvailable = admitCompanyOperation(policy, build, CompanyModule.LEAVE, clock.now())
        if (stillAvailable is AppResult.Failed)
            return reject(access, previousGeneration, stillAvailable)
        val balancesResponse =
            if (employee == null) AppResult.Success(LeavePage<LeaveBalance>(emptyList()))
            else remote.balances(access, employee.id, year, selection.balanceAfter)
        currentCoroutineContext().ensureActive()
        val balanceOwnership = validateLeaveContinuation(access, currentAccess(workspaceId))
        if (balanceOwnership is AppResult.Failed) return balanceOwnership
        val balances =
            when (balancesResponse) {
                is AppResult.Failed -> return reject(access, previousGeneration, balancesResponse)
                is AppResult.Success -> balancesResponse.value
            }
        val requestAvailability =
            admitCompanyOperation(policy, build, CompanyModule.LEAVE, clock.now())
        if (requestAvailability is AppResult.Failed)
            return reject(access, previousGeneration, requestAvailability)
        val requestsResponse =
            if (employee == null) AppResult.Success(LeavePage<LeaveRequestSummary>(emptyList()))
            else remote.requests(access, employee.id, selection.status, selection.requestAfter)
        currentCoroutineContext().ensureActive()
        val current =
            when (val ownership = validateLeaveContinuation(access, currentAccess(workspaceId))) {
                is AppResult.Failed -> return ownership
                is AppResult.Success -> ownership.value
            }
        val finalAvailability =
            admitCompanyOperation(policy, build, CompanyModule.LEAVE, clock.now())
        if (finalAvailability is AppResult.Failed)
            return reject(access, previousGeneration, finalAvailability)
        val requests =
            when (requestsResponse) {
                is AppResult.Failed -> return reject(access, previousGeneration, requestsResponse)
                is AppResult.Success -> requestsResponse.value
            }
        val now = clock.now()
        val maintenance =
            policy.policy.maintenance?.let {
                policy.requestedAt + (it.startsAt - policy.policy.serverTime)
            }
        val readAccess =
            if (
                maintenance != null &&
                    maintenance > now &&
                    maintenance < current.authorizationExpiresAt
            )
                current.copy(
                    authorizationExpiresAt = maintenance,
                    authorizationExpiryCode = "company_maintenance",
                )
            else current
        val snapshot =
            LeaveOverview(
                readAccess,
                Uuid.random().toString(),
                directory,
                selection.copy(employeeId = employee?.id, year = year),
                balances,
                requests,
                now,
                now + LEAVE_FRESHNESS_PERIOD,
            )
        return publish(workspaceId, previousGeneration, snapshot)
    }

    private suspend fun publish(
        workspaceId: String,
        previousGeneration: String?,
        snapshot: LeaveOverview,
    ): AppResult<Unit> {
        currentCoroutineContext().ensureActive()
        when (val saved = cache.replace(snapshot.access, previousGeneration, snapshot)) {
            is AppResult.Failed -> return saved
            is AppResult.Success ->
                if (!saved.value) return leaveFailure("client.leave_changed", FailureKind.Conflict)
        }
        currentCoroutineContext().ensureActive()
        val publication = validateLeaveContinuation(snapshot.access, currentAccess(workspaceId))
        if (publication is AppResult.Failed) return publication
        return AppResult.Success(Unit)
    }

    private suspend fun currentAccess(workspaceId: String): AppResult<LeaveAccess> {
        val session =
            when (val result = sessions.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success ->
                    result.value ?: return leaveFailure("session_revoked", FailureKind.Unauthorized)
            }
        val workspace =
            when (val result = workspaces.current(session.id)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        return admitLeave(workspaceId, workspace, session, build, clock.now())
    }

    private suspend fun reject(
        access: LeaveAccess,
        generation: String?,
        failure: AppResult.Failed,
    ): AppResult.Failed {
        if (leaveFailureInvalidatesCache(failure.failure)) {
            // The authority rejection remains authoritative even when local cleanup fails.
            cache.replace(access, generation, null)
        }
        return failure
    }
}
