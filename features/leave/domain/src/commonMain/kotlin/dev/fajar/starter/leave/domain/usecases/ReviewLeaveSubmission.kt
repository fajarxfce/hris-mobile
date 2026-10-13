package dev.fajar.starter.leave.domain.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.leave.domain.policies.*
import dev.fajar.starter.leave.domain.repositories.*
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** Unsent/rejected input may be cleared; committed input first needs reconciled request facts. */
class ReviewLeaveSubmission(
    private val commands: LeaveSubmissionCommandRepository,
    private val remote: LeaveRepository,
    private val cache: LeaveRequestReplicaRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
) {
    suspend operator fun invoke(workspaceId: String, commandId: String): AppResult<Boolean> =
        withTimeoutOrNull(30.seconds) {
            val access =
                when (val result = currentAccess(workspaceId)) {
                    is AppResult.Failed -> return@withTimeoutOrNull result
                    is AppResult.Success -> result.value
                }
            val stored = commands.find(access, commandId)
            currentCoroutineContext().ensureActive()
            val ownership = validateLeaveContinuation(access, currentAccess(workspaceId))
            if (ownership is AppResult.Failed) return@withTimeoutOrNull ownership
            val record =
                when (stored) {
                    is AppResult.Failed -> return@withTimeoutOrNull stored
                    is AppResult.Success ->
                        stored.value ?: return@withTimeoutOrNull AppResult.Success(false)
                }
            if (!record.command.belongsTo(access))
                return@withTimeoutOrNull leaveFailure(
                    "client.leave_scope_changed",
                    FailureKind.AccessDenied,
                )
            if (record.outcome is LeaveCommandOutcome.Unconfirmed)
                return@withTimeoutOrNull leaveFailure(
                    "client.leave_submission_unconfirmed",
                    FailureKind.Conflict,
                )
            if (record.outcome is LeaveCommandOutcome.Confirmed) {
                val response =
                    remote.details(access, LeaveRequestSelection(record.command.requestId))
                currentCoroutineContext().ensureActive()
                val continuation = validateLeaveContinuation(access, currentAccess(workspaceId))
                if (continuation is AppResult.Failed) return@withTimeoutOrNull continuation
                val canonical =
                    when (response) {
                        is AppResult.Failed -> return@withTimeoutOrNull response
                        is AppResult.Success -> response.value
                    }
                val cached =
                    cache
                        .observeRequest(access, LeaveRequestSelection(record.command.requestId))
                        .first()
                currentCoroutineContext().ensureActive()
                val cacheOwnership = validateLeaveContinuation(access, currentAccess(workspaceId))
                if (cacheOwnership is AppResult.Failed) return@withTimeoutOrNull cacheOwnership
                val snapshot =
                    when (cached) {
                        is AppResult.Failed -> return@withTimeoutOrNull cached
                        is AppResult.Success -> cached.value
                    }
                val lease = validateLeaveActionSnapshot(access, snapshot, clock.now())
                if (lease is AppResult.Failed) return@withTimeoutOrNull lease
                val cachedRequest = snapshot?.request
                if (
                    canonical.id != record.command.requestId ||
                        canonical.employeeId != record.command.selection.employeeId ||
                        canonical.policy.typeId != record.command.selection.typeId ||
                        cachedRequest == null ||
                        cachedRequest.id != canonical.id ||
                        cachedRequest.version < canonical.version ||
                        canonical.version < record.outcome.version
                )
                    return@withTimeoutOrNull leaveFailure(
                        "client.leave_updates_pending",
                        FailureKind.Conflict,
                    )
            }
            val publication = validateLeaveContinuation(access, currentAccess(workspaceId))
            if (publication is AppResult.Failed) return@withTimeoutOrNull publication
            val removal = commands.remove(access, record)
            currentCoroutineContext().ensureActive()
            val current = validateLeaveContinuation(access, currentAccess(workspaceId))
            if (current is AppResult.Failed) current else removal
        } ?: leaveFailure("client.leave_timeout", FailureKind.Timeout)

    private suspend fun currentAccess(workspaceId: String): AppResult<LeaveAccess> {
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
            val admitted = admitLeave(workspaceId, workspace, latest, build, clock.now())
        ) {
            is AppResult.Failed -> admitted
            is AppResult.Success ->
                when (val permitted = admitSelfLeavePreparation(admitted.value)) {
                    is AppResult.Failed -> permitted
                    is AppResult.Success -> admitted
                }
        }
    }
}
