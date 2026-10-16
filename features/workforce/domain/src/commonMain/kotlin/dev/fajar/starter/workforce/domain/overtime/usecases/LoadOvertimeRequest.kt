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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Owns acquisition, current self-service authority and atomic publication. */
class LoadOvertimeRequest(
    private val workforce: WorkforceRepository,
    private val remote: OvertimeRepository,
    private val cache: OvertimeRequestReplicaRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
    private val execution: Mutex = Mutex(),
) {

    suspend operator fun invoke(
        workspaceId: String,
        selection: OvertimeRequestSelection,
        acquisition: OvertimeRequestAcquisition = OvertimeRequestAcquisition.FRESH,
    ): AppResult<Unit> =
        withTimeoutOrNull(30.seconds) {
            val valid = validateOvertimeRequestSelection(selection)
            if (valid is AppResult.Failed) return@withTimeoutOrNull valid
            if (acquisition == OvertimeRequestAcquisition.LOCAL_FIRST) {
                val access =
                    when (val current = currentAccess(workspaceId)) {
                        is AppResult.Failed -> return@withTimeoutOrNull current
                        is AppResult.Success -> current.value
                    }
                val admitted = readContinuation(workspaceId, access)
                if (admitted is AppResult.Failed) return@withTimeoutOrNull admitted
                val saved = cache.observeRequest(access, selection).first()
                currentCoroutineContext().ensureActive()
                val owner = readContinuation(workspaceId, access)
                if (owner is AppResult.Failed) return@withTimeoutOrNull owner
                when (saved) {
                    is AppResult.Failed -> return@withTimeoutOrNull saved
                    is AppResult.Success ->
                        if (saved.value?.value?.selection == selection)
                            return@withTimeoutOrNull validateStoredOvertime(access, saved.value)
                }
            }
            execution.withLock { acquire(workspaceId, selection) }
        } ?: overtimeFailure("client.overtime_timeout", FailureKind.Timeout)

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
        val admitted = readContinuation(workspaceId, access)
        if (admitted is AppResult.Failed) return admitted
        val generation =
            when (val current = cache.requestCheckpoint(access)) {
                is AppResult.Failed -> return current
                is AppResult.Success -> current.value
            }
        currentCoroutineContext().ensureActive()
        val initialOwnership = readContinuation(workspaceId, access)
        if (initialOwnership is AppResult.Failed) return initialOwnership
        val requestedAt = clock.now()
        val policyResponse = workspaces.clientPolicy(access.sessionId, access.companyId)
        currentCoroutineContext().ensureActive()
        val policyOwnership = readContinuation(workspaceId, access)
        if (policyOwnership is AppResult.Failed) return policyOwnership
        val policy =
            when (policyResponse) {
                is AppResult.Failed ->
                    return reject(access, selection.requestId, generation, policyResponse)
                is AppResult.Success -> CompanyPolicySnapshot(policyResponse.value, requestedAt)
            }
        val availability =
            admitCompanyOperation(policy, build, CompanyModule.WORKFORCE, clock.now())
        if (availability is AppResult.Failed)
            return reject(access, selection.requestId, generation, availability)
        val employment = verifyEmployment(workspaceId, access, selection, generation)
        if (employment is AppResult.Failed) return employment
        val stillAvailable =
            admitCompanyOperation(policy, build, CompanyModule.WORKFORCE, clock.now())
        if (stillAvailable is AppResult.Failed)
            return reject(access, selection.requestId, generation, stillAvailable)
        val latest = selection.copy(historyAfter = null)
        val response = remote.details(access, latest)
        val receivedAt = clock.now()
        currentCoroutineContext().ensureActive()
        val current =
            when (val ownership = readContinuation(workspaceId, access)) {
                is AppResult.Failed -> return ownership
                is AppResult.Success -> ownership.value
            }
        val finalAvailability =
            admitCompanyOperation(policy, build, CompanyModule.WORKFORCE, clock.now())
        if (finalAvailability is AppResult.Failed)
            return reject(access, selection.requestId, generation, finalAvailability)
        val result =
            when (response) {
                is AppResult.Failed ->
                    return reject(access, selection.requestId, generation, response)
                is AppResult.Success -> response.value
            }
        if (
            result.request.id != selection.requestId ||
                result.request.employeeId != selection.employeeId
        )
            return overtimeFailure("client.invalid_response", FailureKind.Unexpected)
        val history =
            when (val older = acquireHistory(workspaceId, access, selection, result, generation)) {
                is AppResult.Failed -> return older
                is AppResult.Success -> older.value
            }
        val publicationAccess =
            admitCompanyOperation(policy, build, CompanyModule.WORKFORCE, clock.now())
        if (publicationAccess is AppResult.Failed)
            return reject(access, selection.requestId, generation, publicationAccess)
        val view = OvertimeRequestView(latest, result)
        val now = clock.now()
        val readAccess = boundOvertimeAuthorization(current, policy, now)
        val snapshot =
            OvertimeSnapshot(
                readAccess,
                Uuid.random().toString(),
                view,
                receivedAt,
                receivedAt + OVERTIME_FRESHNESS_PERIOD,
            )
        currentCoroutineContext().ensureActive()
        when (
            val saved =
                cache.replaceRequest(access, selection.requestId, generation, snapshot, history)
        ) {
            is AppResult.Failed -> return saved
            is AppResult.Success ->
                if (!saved.value)
                    return overtimeFailure("client.overtime_changed", FailureKind.Conflict)
        }
        currentCoroutineContext().ensureActive()
        val publication = readContinuation(workspaceId, access)
        if (publication is AppResult.Failed) return publication
        return AppResult.Success(Unit)
    }

    private suspend fun verifyEmployment(
        workspaceId: String,
        access: WorkforceAccess,
        selection: OvertimeRequestSelection,
        generation: String?,
    ): AppResult<Unit> {
        val directoryResponse = workforce.selfEmployments(access, selection.employmentAfter)
        currentCoroutineContext().ensureActive()
        val directoryOwnership = readContinuation(workspaceId, access)
        if (directoryOwnership is AppResult.Failed) return directoryOwnership
        val directory =
            when (directoryResponse) {
                is AppResult.Failed ->
                    return reject(access, selection.requestId, generation, directoryResponse)
                is AppResult.Success -> directoryResponse.value
            }
        if (directory.accountId != access.accountId || directory.companyId != access.companyId)
            return overtimeFailure("client.invalid_response", FailureKind.Unexpected)
        if (directory.items.none { it.id == selection.employeeId })
            return reject(
                access,
                selection.requestId,
                generation,
                overtimeFailure("employee_not_found", FailureKind.AccessDenied),
            )
        return AppResult.Success(Unit)
    }

    /** Historical activity must agree with the current request version before it is stored. */
    private suspend fun acquireHistory(
        workspaceId: String,
        access: WorkforceAccess,
        selection: OvertimeRequestSelection,
        details: OvertimeDetails,
        generation: String?,
    ): AppResult<OvertimeHistoryPage?> {
        val after = selection.historyAfter ?: return AppResult.Success(null)
        val response = remote.details(access, selection)
        currentCoroutineContext().ensureActive()
        val owner = readContinuation(workspaceId, access)
        if (owner is AppResult.Failed) return owner
        val older =
            when (response) {
                is AppResult.Failed ->
                    return reject(access, selection.requestId, generation, response)
                is AppResult.Success -> response.value
            }
        if (
            older.request.id != details.request.id ||
                older.request.employeeId != details.request.employeeId ||
                older.request.version != details.request.version
        )
            return overtimeFailure("client.overtime_changed", FailureKind.Conflict)
        return AppResult.Success(
            OvertimeHistoryPage(details.request.id, details.request.version, after, older.history)
        )
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
        requestId: String,
        generation: String?,
        failure: AppResult.Failed,
    ): AppResult.Failed {
        if (overtimeFailureInvalidatesCache(failure.failure)) {
            withTimeoutOrNull(2.seconds) { cache.restrict(access, failure.failure) }
            withTimeoutOrNull(2.seconds) {
                cache.replaceRequest(access, requestId, generation, null)
            }
        }
        // Cleanup failure cannot replace the authoritative rejection.
        return failure
    }
}
