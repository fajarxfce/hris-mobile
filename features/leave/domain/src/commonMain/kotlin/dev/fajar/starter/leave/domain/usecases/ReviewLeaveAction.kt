package dev.fajar.starter.leave.domain.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.leave.domain.policies.*
import dev.fajar.starter.leave.domain.repositories.*
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first

/**
 * Retires confirmed/rejected evidence after canonical review; uncertain attempts remain replayable.
 */
class ReviewLeaveAction(
    private val commands: LeaveCommandRepository,
    private val remote: LeaveRepository,
    private val cache: LeaveRequestCacheRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
) {
    suspend operator fun invoke(workspaceId: String, commandId: String): AppResult<Boolean> {
        val access =
            when (val result = currentAccess(workspaceId)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        val record =
            when (val result = commands.find(access, commandId)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value ?: return AppResult.Success(false)
            }
        currentCoroutineContext().ensureActive()
        if (!record.command.belongsTo(access))
            return leaveFailure("client.leave_scope_changed", FailureKind.AccessDenied)
        if (record.outcome is LeaveCommandOutcome.Unconfirmed)
            return leaveFailure("client.leave_action_unconfirmed", FailureKind.Conflict)
        if (record.outcome != LeaveCommandOutcome.Pending) {
            val canonical =
                when (
                    val result =
                        remote.details(access, LeaveRequestSelection(record.command.requestId))
                ) {
                    is AppResult.Failed -> return result
                    is AppResult.Success -> result.value
                }
            currentCoroutineContext().ensureActive()
            val snapshot =
                when (val result = cache.observeRequest(access).first()) {
                    is AppResult.Failed -> return result
                    is AppResult.Success -> result.value
                }
            currentCoroutineContext().ensureActive()
            val lease = validateLeaveActionSnapshot(access, snapshot, clock.now())
            if (lease is AppResult.Failed) return lease
            val receipt = (record.outcome as? LeaveCommandOutcome.Confirmed)?.version
            val cached = snapshot?.request
            if (
                canonical.id != record.command.requestId ||
                    cached == null ||
                    cached.id != canonical.id ||
                    cached.version < canonical.version ||
                    (receipt != null && canonical.version < receipt)
            )
                return leaveFailure("client.leave_updates_pending", FailureKind.Conflict)
        }
        val ownership = validateLeaveContinuation(access, currentAccess(workspaceId))
        if (ownership is AppResult.Failed) return ownership
        // This revision CAS cannot delete a command whose replay began during review.
        return commands.remove(access, record)
    }

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
