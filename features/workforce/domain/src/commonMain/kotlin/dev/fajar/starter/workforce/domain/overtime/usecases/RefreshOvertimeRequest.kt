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
class RefreshOvertimeRequest(
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
        selection: OvertimeRequestSelection,
    ): AppResult<Unit> =
        execution.withLock {
            withTimeoutOrNull(30.seconds) { acquire(workspaceId, selection) }
                ?: overtimeFailure("client.overtime_timeout", FailureKind.Timeout)
        }

    private suspend fun acquire(
        workspaceId: String,
        selection: OvertimeRequestSelection,
    ): AppResult<Unit> {
        val valid = validateOvertimeRequestSelection(selection)
        if (valid is AppResult.Failed) return valid
        val access =
            when (val current = currentAccess(workspaceId)) {
                is AppResult.Failed -> return current
                is AppResult.Success -> current.value
            }
        val generation =
            when (val current = cache.requestGeneration(access)) {
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
        if (directory.items.none { it.id == selection.employeeId })
            return reject(
                access,
                generation,
                overtimeFailure("employee_not_found", FailureKind.AccessDenied),
            )
        val stillAvailable =
            admitCompanyOperation(policy, build, CompanyModule.WORKFORCE, clock.now())
        if (stillAvailable is AppResult.Failed) return reject(access, generation, stillAvailable)
        val response = remote.details(access, selection)
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
        if (
            result.request.id != selection.requestId ||
                result.request.employeeId != selection.employeeId
        )
            return overtimeFailure("client.invalid_response", FailureKind.Unexpected)
        val view = OvertimeRequestView(selection, result)
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
        when (val saved = cache.replaceRequest(access, generation, snapshot)) {
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
            cache.replaceRequest(access, generation, null)
        // Cleanup failure cannot replace the authoritative rejection.
        return failure
    }
}
