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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns a finite self-service read and complete publication, independently of overview refreshes.
 */
class LoadLeaveRequest(
    private val remote: LeaveRepository,
    private val cache: LeaveRequestReplicaRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
    private val execution: Mutex = Mutex(),
) {
    suspend operator fun invoke(
        workspaceId: String,
        selection: LeaveRequestSelection,
        acquisition: LeaveRequestAcquisition = LeaveRequestAcquisition.FRESH,
    ): AppResult<Unit> =
        withTimeoutOrNull(30.seconds) {
            val valid = validateLeaveRequestSelection(selection)
            if (valid is AppResult.Failed) return@withTimeoutOrNull valid
            if (acquisition == LeaveRequestAcquisition.LOCAL_FIRST) {
                val access =
                    when (val current = currentAccess(workspaceId)) {
                        is AppResult.Failed -> return@withTimeoutOrNull current
                        is AppResult.Success -> current.value
                    }
                val saved = cache.observeRequest(access, selection).first()
                currentCoroutineContext().ensureActive()
                val ownership = validateLeaveContinuation(access, currentAccess(workspaceId))
                if (ownership is AppResult.Failed) return@withTimeoutOrNull ownership
                when (saved) {
                    is AppResult.Failed -> return@withTimeoutOrNull saved
                    is AppResult.Success ->
                        if (saved.value?.selection == selection)
                            return@withTimeoutOrNull validateStoredLeaveRequest(access, saved.value)
                }
            }
            execution.withLock { acquire(workspaceId, selection) }
        } ?: leaveFailure("client.leave_timeout", FailureKind.Timeout)

    private suspend fun acquire(
        workspaceId: String,
        selection: LeaveRequestSelection,
    ): AppResult<Unit> {
        val valid = validateLeaveRequestSelection(selection)
        if (valid is AppResult.Failed) return valid
        val access =
            when (val current = currentAccess(workspaceId)) {
                is AppResult.Failed -> return current
                is AppResult.Success -> current.value
            }
        val generation =
            when (val current = cache.requestCheckpoint(access)) {
                is AppResult.Failed -> return current
                is AppResult.Success -> current.value
            }
        currentCoroutineContext().ensureActive()
        val initialOwnership = validateLeaveContinuation(access, currentAccess(workspaceId))
        if (initialOwnership is AppResult.Failed) return initialOwnership
        val requestedAt = clock.now()
        val policyResponse = workspaces.clientPolicy(access.sessionId, access.companyId)
        currentCoroutineContext().ensureActive()
        val policyOwnership = validateLeaveContinuation(access, currentAccess(workspaceId))
        if (policyOwnership is AppResult.Failed) return policyOwnership
        val policy =
            when (policyResponse) {
                is AppResult.Failed ->
                    return reject(access, selection.requestId, generation, policyResponse)
                is AppResult.Success -> CompanyPolicySnapshot(policyResponse.value, requestedAt)
            }
        val availability = admitCompanyOperation(policy, build, CompanyModule.LEAVE, clock.now())
        if (availability is AppResult.Failed)
            return reject(access, selection.requestId, generation, availability)
        val latest = LeaveRequestSelection(selection.requestId)
        val response = remote.details(access, latest)
        val receivedAt = clock.now()
        currentCoroutineContext().ensureActive()
        val current =
            when (val ownership = validateLeaveContinuation(access, currentAccess(workspaceId))) {
                is AppResult.Failed -> return ownership
                is AppResult.Success -> ownership.value
            }
        val stillAvailable = admitCompanyOperation(policy, build, CompanyModule.LEAVE, clock.now())
        if (stillAvailable is AppResult.Failed)
            return reject(access, selection.requestId, generation, stillAvailable)
        val details =
            when (response) {
                is AppResult.Failed ->
                    return reject(access, selection.requestId, generation, response)
                is AppResult.Success -> response.value
            }
        if (details.id != selection.requestId)
            return leaveFailure("client.invalid_response", FailureKind.Unexpected)
        val history =
            when (val older = acquireHistory(workspaceId, access, selection, details, generation)) {
                is AppResult.Failed -> return older
                is AppResult.Success -> older.value
            }
        val publicationAccess =
            admitCompanyOperation(policy, build, CompanyModule.LEAVE, clock.now())
        if (publicationAccess is AppResult.Failed)
            return reject(access, selection.requestId, generation, publicationAccess)
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
            LeaveRequestSnapshot(
                readAccess,
                Uuid.random().toString(),
                latest,
                details,
                receivedAt,
                receivedAt + LEAVE_FRESHNESS_PERIOD,
            )
        when (
            val publication =
                cache.replaceRequest(access, selection.requestId, generation, snapshot, history)
        ) {
            is AppResult.Failed -> return publication
            is AppResult.Success ->
                if (!publication.value)
                    return leaveFailure("client.leave_changed", FailureKind.Conflict)
        }
        currentCoroutineContext().ensureActive()
        val publication = validateLeaveContinuation(access, currentAccess(workspaceId))
        if (publication is AppResult.Failed) return publication
        return AppResult.Success(Unit)
    }

    /** Older activity must belong to the same current request version before publication. */
    private suspend fun acquireHistory(
        workspaceId: String,
        access: LeaveAccess,
        selection: LeaveRequestSelection,
        details: LeaveRequestDetails,
        generation: String?,
    ): AppResult<LeaveHistoryPage?> {
        val after = selection.historyAfter ?: return AppResult.Success(null)
        val response = remote.details(access, selection)
        currentCoroutineContext().ensureActive()
        val ownership = validateLeaveContinuation(access, currentAccess(workspaceId))
        if (ownership is AppResult.Failed) return ownership
        val older =
            when (response) {
                is AppResult.Failed ->
                    return reject(access, selection.requestId, generation, response)
                is AppResult.Success -> response.value
            }
        if (older.id != details.id || older.version != details.version)
            return leaveFailure("client.leave_changed", FailureKind.Conflict)
        return AppResult.Success(
            LeaveHistoryPage(details.id, details.version, after, older.history)
        )
    }

    private suspend fun currentAccess(workspaceId: String): AppResult<LeaveAccess> {
        val session =
            when (val current = sessions.current()) {
                is AppResult.Failed -> return current
                is AppResult.Success ->
                    current.value
                        ?: return leaveFailure("session_revoked", FailureKind.Unauthorized)
            }
        val workspace =
            when (val current = workspaces.current(session.id)) {
                is AppResult.Failed -> return current
                is AppResult.Success -> current.value
            }
        return admitLeave(workspaceId, workspace, session, build, clock.now())
    }

    private suspend fun reject(
        access: LeaveAccess,
        requestId: String,
        generation: String?,
        failure: AppResult.Failed,
    ): AppResult.Failed {
        if (leaveFailureInvalidatesCache(failure.failure))
            cache.replaceRequest(access, requestId, generation, null)
        return failure
    }
}
