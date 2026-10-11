@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.workforce.domain.attendance.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workforce.domain.attendance.entities.*
import dev.fajar.starter.workforce.domain.attendance.policies.*
import dev.fajar.starter.workforce.domain.attendance.repositories.*
import dev.fajar.starter.workforce.domain.entities.*
import dev.fajar.starter.workforce.domain.policies.*
import dev.fajar.starter.workforce.domain.repositories.*
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
class RefreshAttendance(
    private val remote: WorkforceRepository,
    private val attendance: AttendanceRepository,
    private val cache: AttendanceCacheRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
) {
    private val execution = Mutex()

    suspend operator fun invoke(
        workspaceId: String,
        selection: AttendanceSelection,
    ): AppResult<Unit> =
        execution.withLock {
            withTimeoutOrNull(30.seconds) { acquire(workspaceId, selection) }
                ?: calendarFailure("client.attendance_timeout", FailureKind.Timeout)
        }

    private suspend fun acquire(
        workspaceId: String,
        selection: AttendanceSelection,
    ): AppResult<Unit> {
        val validation = validateAttendanceSelection(selection)
        if (validation is AppResult.Failed) return validation
        val access =
            when (val current = currentAccess(workspaceId)) {
                is AppResult.Failed -> return current
                is AppResult.Success -> current.value
            }
        val previousGeneration =
            when (val saved = cache.generation(access, selection.window)) {
                is AppResult.Failed -> return saved
                is AppResult.Success -> saved.value
            }
        val requestedAt = clock.now()
        val policyResponse = workspaces.clientPolicy(access.sessionId, access.companyId)
        currentCoroutineContext().ensureActive()
        val policyOwnership = validateWorkforceContinuation(access, currentAccess(workspaceId))
        if (policyOwnership is AppResult.Failed) return policyOwnership
        val policy =
            when (policyResponse) {
                is AppResult.Failed ->
                    return reject(access, previousGeneration, policyResponse, selection.window)
                is AppResult.Success -> CompanyPolicySnapshot(policyResponse.value, requestedAt)
            }
        val availability =
            admitCompanyOperation(policy, build, CompanyModule.WORKFORCE, clock.now())
        if (availability is AppResult.Failed)
            return reject(access, previousGeneration, availability, selection.window)
        return loadSnapshot(workspaceId, selection, access, previousGeneration, policy)
    }

    private suspend fun loadSnapshot(
        workspaceId: String,
        selection: AttendanceSelection,
        access: WorkforceAccess,
        previousGeneration: String?,
        policy: CompanyPolicySnapshot,
    ): AppResult<Unit> {
        val directoryResponse = remote.selfEmployments(access, selection.after)
        currentCoroutineContext().ensureActive()
        val directoryOwnership = validateWorkforceContinuation(access, currentAccess(workspaceId))
        if (directoryOwnership is AppResult.Failed) return directoryOwnership
        val directory =
            when (directoryResponse) {
                is AppResult.Failed ->
                    return reject(access, previousGeneration, directoryResponse, selection.window)
                is AppResult.Success -> directoryResponse.value
            }
        if (directory.accountId != access.accountId || directory.companyId != access.companyId)
            return calendarFailure("client.invalid_response", FailureKind.Unexpected)
        val period =
            when (val requested = attendancePeriod(directory.asOf, selection)) {
                is AppResult.Failed -> return requested
                is AppResult.Success -> requested.value
            }
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
                previousGeneration,
                calendarFailure("employee_not_found", FailureKind.AccessDenied),
                selection.window,
            )
        val stillAvailable =
            admitCompanyOperation(policy, build, CompanyModule.WORKFORCE, clock.now())
        if (stillAvailable is AppResult.Failed)
            return reject(access, previousGeneration, stillAvailable, selection.window)
        val response =
            if (employee == null) AppResult.Success(null)
            else remote.calendar(access, employee.id, period)
        currentCoroutineContext().ensureActive()
        val current =
            when (
                val ownership = validateWorkforceContinuation(access, currentAccess(workspaceId))
            ) {
                is AppResult.Failed -> return ownership
                is AppResult.Success -> ownership.value
            }
        val finalAvailability =
            admitCompanyOperation(policy, build, CompanyModule.WORKFORCE, clock.now())
        if (finalAvailability is AppResult.Failed)
            return reject(access, previousGeneration, finalAvailability, selection.window)
        val calendar =
            when (response) {
                is AppResult.Failed ->
                    return reject(access, previousGeneration, response, selection.window)
                is AppResult.Success -> response.value
            }
        val history =
            if (employee == null) emptyList()
            else {
                val result = attendance.days(access, employee.id, period)
                currentCoroutineContext().ensureActive()
                val continued = validateWorkforceContinuation(access, currentAccess(workspaceId))
                if (continued is AppResult.Failed) return continued
                when (result) {
                    is AppResult.Failed ->
                        return reject(access, previousGeneration, result, selection.window)
                    is AppResult.Success -> result.value
                }
            }
        val historyAvailability =
            admitCompanyOperation(policy, build, CompanyModule.WORKFORCE, clock.now())
        if (historyAvailability is AppResult.Failed)
            return reject(access, previousGeneration, historyAvailability, selection.window)
        val now = clock.now()
        val maintenance =
            policy.policy.maintenance?.let {
                policy.requestedAt + (it.startsAt - policy.policy.serverTime)
            }
        val readAccess =
            if (maintenance != null && maintenance > now && maintenance < current.readUntil)
                current.copy(readUntil = maintenance, expiryCode = "company_maintenance")
            else current
        val snapshot =
            AttendanceSnapshot(
                readAccess,
                Uuid.random().toString(),
                directory,
                employee?.id,
                period,
                calendar,
                history,
                now,
                minOf(now + ATTENDANCE_CACHE_PERIOD, readAccess.readUntil),
                after = selection.after,
            )
        return publish(workspaceId, previousGeneration, snapshot, selection.window)
    }

    private suspend fun publish(
        workspaceId: String,
        previousGeneration: String?,
        snapshot: AttendanceSnapshot,
        window: AttendanceWindow,
    ): AppResult<Unit> {
        currentCoroutineContext().ensureActive()
        when (val saved = cache.replace(snapshot.access, previousGeneration, snapshot, window)) {
            is AppResult.Failed -> return saved
            is AppResult.Success ->
                if (!saved.value)
                    return calendarFailure("client.attendance_changed", FailureKind.Conflict)
        }
        currentCoroutineContext().ensureActive()
        val publication = validateWorkforceContinuation(snapshot.access, currentAccess(workspaceId))
        if (publication is AppResult.Failed) return publication
        return AppResult.Success(Unit)
    }

    private suspend fun currentAccess(workspaceId: String): AppResult<WorkforceAccess> {
        val session =
            when (val result = sessions.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success ->
                    result.value
                        ?: return calendarFailure("session_revoked", FailureKind.Unauthorized)
            }
        val workspace =
            when (val result = workspaces.current(session.id)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        return admitWorkforce(workspaceId, workspace, session, build, clock.now())
    }

    private suspend fun reject(
        access: WorkforceAccess,
        generation: String?,
        failure: AppResult.Failed,
        window: AttendanceWindow,
    ): AppResult.Failed {
        if (calendarFailureInvalidatesCache(failure.failure)) {
            // The authority rejection remains authoritative even when local cleanup fails.
            cache.replace(access, generation, null, window)
        }
        return failure
    }
}
