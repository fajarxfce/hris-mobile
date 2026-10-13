@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.workforce.domain.overtime.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workforce.domain.entities.*
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
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Owns acquisition, current self-service authority and atomic publication. */
class RefreshOvertime(
    private val workforce: WorkforceRepository,
    private val remote: OvertimeRepository,
    private val cache: OvertimeCacheRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
) {
    private val execution = Mutex()

    suspend operator fun invoke(
        workspaceId: String,
        selection: OvertimeSelection,
    ): AppResult<Unit> =
        execution.withLock {
            withTimeoutOrNull(30.seconds) { acquire(workspaceId, selection) }
                ?: overtimeFailure("client.overtime_timeout", FailureKind.Timeout)
        }

    private suspend fun acquire(
        workspaceId: String,
        selection: OvertimeSelection,
    ): AppResult<Unit> {
        val valid = validateOvertimeSelection(selection)
        if (valid is AppResult.Failed) return valid
        val access =
            when (val current = currentAccess(workspaceId)) {
                is AppResult.Failed -> return current
                is AppResult.Success -> current.value
            }
        val generation =
            when (val current = cache.generation(access)) {
                is AppResult.Failed -> return current
                is AppResult.Success -> current.value
            }
        currentCoroutineContext().ensureActive()
        val initialOwnership = validateOvertimeContinuation(access, currentAccess(workspaceId))
        if (initialOwnership is AppResult.Failed) return initialOwnership
        val requestedAt = clock.now()
        val policyResponse = workspaces.clientPolicy(access.sessionId, access.companyId)
        currentCoroutineContext().ensureActive()
        val policyOwnership = validateOvertimeContinuation(access, currentAccess(workspaceId))
        if (policyOwnership is AppResult.Failed) return policyOwnership
        val policy =
            when (policyResponse) {
                is AppResult.Failed -> return reject(access, generation, policyResponse)
                is AppResult.Success -> CompanyPolicySnapshot(policyResponse.value, requestedAt)
            }
        val availability =
            admitCompanyOperation(policy, build, CompanyModule.WORKFORCE, clock.now())
        if (availability is AppResult.Failed) return reject(access, generation, availability)
        return loadOverview(workspaceId, selection, access, generation, policy)
    }

    private suspend fun loadOverview(
        workspaceId: String,
        selection: OvertimeSelection,
        access: WorkforceAccess,
        generation: String?,
        policy: CompanyPolicySnapshot,
    ): AppResult<Unit> {
        val directoryResponse = workforce.selfEmployments(access, selection.employmentAfter)
        currentCoroutineContext().ensureActive()
        val directoryOwnership = validateOvertimeContinuation(access, currentAccess(workspaceId))
        if (directoryOwnership is AppResult.Failed) return directoryOwnership
        val directory =
            when (directoryResponse) {
                is AppResult.Failed -> return reject(access, generation, directoryResponse)
                is AppResult.Success -> directoryResponse.value
            }
        if (directory.accountId != access.accountId || directory.companyId != access.companyId)
            return overtimeFailure("client.invalid_response", FailureKind.Unexpected)
        val employee =
            if (selection.employeeId == null)
                directory.items.firstOrNull {
                    it.status in
                        setOf(WorkforceEmploymentStatus.ACTIVE, WorkforceEmploymentStatus.PROBATION)
                } ?: directory.items.firstOrNull()
            else directory.items.firstOrNull { it.id == selection.employeeId }
        if (selection.employeeId != null && employee == null)
            return reject(
                access,
                generation,
                overtimeFailure("employee_not_found", FailureKind.AccessDenied),
            )
        val selected =
            selection.copy(
                employeeId = employee?.id,
                period = selection.period ?: overtimeMonth(directory.asOf),
            )
        val selectionValidity = validateOvertimeSelection(selected)
        if (selectionValidity is AppResult.Failed) return selectionValidity
        val stillAvailable =
            admitCompanyOperation(policy, build, CompanyModule.WORKFORCE, clock.now())
        if (stillAvailable is AppResult.Failed) return reject(access, generation, stillAvailable)
        val response =
            if (employee == null) AppResult.Success(OvertimePage<OvertimeSummary>(emptyList()))
            else
                remote.requests(
                    access,
                    employee.id,
                    requireNotNull(selected.period),
                    selected.status,
                    selected.requestAfter,
                )
        currentCoroutineContext().ensureActive()
        val current =
            when (
                val ownership = validateOvertimeContinuation(access, currentAccess(workspaceId))
            ) {
                is AppResult.Failed -> return ownership
                is AppResult.Success -> ownership.value
            }
        val finalAvailability =
            admitCompanyOperation(policy, build, CompanyModule.WORKFORCE, clock.now())
        if (finalAvailability is AppResult.Failed)
            return reject(access, generation, finalAvailability)
        val result =
            when (response) {
                is AppResult.Failed -> return reject(access, generation, response)
                is AppResult.Success -> response.value
            }
        if (result.items.any { it.employeeId != selected.employeeId })
            return overtimeFailure("client.invalid_response", FailureKind.Unexpected)
        val view = OvertimeOverview(directory, selected, result)
        val now = clock.now()
        val readAccess = boundOvertimeAuthorization(current, policy, now)
        val snapshot =
            OvertimeSnapshot(
                readAccess,
                Uuid.random().toString(),
                view,
                now,
                now + OVERTIME_FRESHNESS_PERIOD,
            )
        currentCoroutineContext().ensureActive()
        when (val saved = cache.replace(access, generation, snapshot)) {
            is AppResult.Failed -> return saved
            is AppResult.Success ->
                if (!saved.value)
                    return overtimeFailure("client.overtime_changed", FailureKind.Conflict)
        }
        currentCoroutineContext().ensureActive()
        val publication = validateOvertimeContinuation(access, currentAccess(workspaceId))
        if (publication is AppResult.Failed) return publication
        return AppResult.Success(Unit)
    }

    private suspend fun currentAccess(workspaceId: String): AppResult<WorkforceAccess> {
        val session =
            when (val current = sessions.current()) {
                is AppResult.Failed -> return current
                is AppResult.Success ->
                    current.value
                        ?: return overtimeFailure("session_revoked", FailureKind.Unauthorized)
            }
        val workspace =
            when (val current = workspaces.current(session.id)) {
                is AppResult.Failed -> return current
                is AppResult.Success -> current.value
            }
        return admitOvertime(workspaceId, workspace, session, build, clock.now())
    }

    private suspend fun reject(
        access: WorkforceAccess,
        generation: String?,
        failure: AppResult.Failed,
    ): AppResult.Failed {
        if (overtimeFailureInvalidatesCache(failure.failure))
            cache.replace(access, generation, null)
        // Cleanup failure cannot replace the authoritative rejection.
        return failure
    }
}
