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

/**
 * Owns a finite self-service read and complete publication, independently of overview refreshes.
 */
class RefreshLeaveRequest(
    private val remote: LeaveRepository,
    private val cache: LeaveRequestCacheRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
) {
    private val execution = Mutex()

    suspend operator fun invoke(
        workspaceId: String,
        selection: LeaveRequestSelection,
    ): AppResult<Unit> =
        execution.withLock {
            withTimeoutOrNull(30.seconds) { acquire(workspaceId, selection) }
                ?: leaveFailure("client.leave_timeout", FailureKind.Timeout)
        }

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
            when (val current = cache.requestGeneration(access)) {
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
                is AppResult.Failed -> return reject(access, generation, policyResponse)
                is AppResult.Success -> CompanyPolicySnapshot(policyResponse.value, requestedAt)
            }
        val availability = admitCompanyOperation(policy, build, CompanyModule.LEAVE, clock.now())
        if (availability is AppResult.Failed) return reject(access, generation, availability)
        val response = remote.details(access, selection)
        currentCoroutineContext().ensureActive()
        val current =
            when (val ownership = validateLeaveContinuation(access, currentAccess(workspaceId))) {
                is AppResult.Failed -> return ownership
                is AppResult.Success -> ownership.value
            }
        val stillAvailable = admitCompanyOperation(policy, build, CompanyModule.LEAVE, clock.now())
        if (stillAvailable is AppResult.Failed) return reject(access, generation, stillAvailable)
        val details =
            when (response) {
                is AppResult.Failed -> return reject(access, generation, response)
                is AppResult.Success -> response.value
            }
        if (details.id != selection.requestId)
            return leaveFailure("client.invalid_response", FailureKind.Unexpected)
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
            LeaveRequestSnapshot(
                readAccess,
                Uuid.random().toString(),
                selection,
                details,
                now,
                minOf(now + LEAVE_CACHE_PERIOD, readAccess.readUntil),
            )
        when (val publication = cache.replaceRequest(access, generation, snapshot)) {
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
        generation: String?,
        failure: AppResult.Failed,
    ): AppResult.Failed {
        if (leaveFailureInvalidatesCache(failure.failure))
            cache.replaceRequest(access, generation, null)
        return failure
    }
}
