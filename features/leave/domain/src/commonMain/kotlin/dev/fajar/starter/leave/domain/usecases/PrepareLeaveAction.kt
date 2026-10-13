@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.leave.domain.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.leave.domain.policies.*
import dev.fajar.starter.leave.domain.repositories.*
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first

/** Admits immutable intent from the visible, leased request before any transmission. */
class PrepareLeaveAction(
    private val commands: LeaveCommandRepository,
    private val cache: LeaveRequestReplicaRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
) {
    suspend operator fun invoke(
        workspaceId: String,
        requestId: String,
        version: Long,
        action: LeaveRequestAction,
        reason: String,
    ): AppResult<LeaveCommandRecord> {
        val reasonValidation = validateLeaveActionReason(reason)
        if (reasonValidation is AppResult.Failed) return reasonValidation
        val access =
            when (val result = currentAccess(workspaceId)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        val permission = admitLeaveWrite(access)
        if (permission is AppResult.Failed) return permission
        val snapshot =
            when (
                val result = cache.observeRequest(access, LeaveRequestSelection(requestId)).first()
            ) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        currentCoroutineContext().ensureActive()
        val lease = validateLeaveActionSnapshot(access, snapshot, clock.now())
        if (lease is AppResult.Failed) return lease
        val request = snapshot?.request
        if (request == null || request.id != requestId || request.version != version)
            return leaveFailure("client.leave_review_required", FailureKind.Conflict)
        if (!request.allows(action))
            return leaveFailure("client.leave_action_unavailable", FailureKind.Conflict)
        val existing =
            when (val result = commands.observe(access, requestId).first()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        currentCoroutineContext().ensureActive()
        val continuation = validateLeaveContinuation(access, currentAccess(workspaceId))
        if (continuation is AppResult.Failed) return continuation
        val currentLease = validateLeaveActionSnapshot(access, snapshot, clock.now())
        if (currentLease is AppResult.Failed) return currentLease
        val normalized = reason.trim()
        if (existing != null) return retained(existing, version, action, normalized)
        val command =
            LeaveCommand(
                Uuid.random().toString(),
                access.accountId,
                access.audience,
                access.companyId,
                requestId,
                version,
                action,
                normalized,
                clock.now(),
            )
        when (val result = commands.insert(access, command)) {
            is AppResult.Failed -> return result
            is AppResult.Success ->
                when (result.value) {
                    LeaveCommandAdmission.CAPACITY_EXCEEDED ->
                        return leaveFailure("client.command_queue_full", FailureKind.Storage)
                    LeaveCommandAdmission.SCOPE_CHANGED ->
                        return leaveFailure("client.leave_scope_changed", FailureKind.AccessDenied)
                    LeaveCommandAdmission.STORED,
                    LeaveCommandAdmission.OCCUPIED -> Unit
                }
        }
        currentCoroutineContext().ensureActive()
        val stored =
            when (val result = commands.observe(access, requestId).first()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        currentCoroutineContext().ensureActive()
        val publication = validateLeaveContinuation(access, currentAccess(workspaceId))
        if (publication is AppResult.Failed) return publication
        return retained(stored, version, action, normalized)
    }

    private fun retained(
        record: LeaveCommandRecord?,
        version: Long,
        action: LeaveRequestAction,
        reason: String,
    ): AppResult<LeaveCommandRecord> =
        if (
            record != null &&
                record.command.expectedVersion == version &&
                record.command.action == action &&
                record.command.reason == reason
        )
            AppResult.Success(record)
        else leaveFailure("client.leave_action_pending", FailureKind.Conflict)

    private suspend fun currentAccess(workspaceId: String): AppResult<LeaveAccess> {
        val session =
            when (val result = sessions.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success ->
                    result.value ?: return leaveFailure("session_revoked", FailureKind.Unauthorized)
            }
        currentCoroutineContext().ensureActive()
        val workspace =
            when (val result = workspaces.current(session.id)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        currentCoroutineContext().ensureActive()
        return admitLeave(workspaceId, workspace, session, build, clock.now())
    }
}
