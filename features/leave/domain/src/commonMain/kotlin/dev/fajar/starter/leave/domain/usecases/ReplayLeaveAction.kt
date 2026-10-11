package dev.fajar.starter.leave.domain.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.leave.domain.policies.*
import dev.fajar.starter.leave.domain.repositories.*
import dev.fajar.starter.sync.domain.*
import dev.fajar.starter.workspace.domain.entities.CompanyModule
import dev.fajar.starter.workspace.domain.entities.CompanyPolicySnapshot
import dev.fajar.starter.workspace.domain.policies.admitCompanyOperation
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes manual/background delivery; every attempt reuses the retained operation and body. */
class ReplayLeaveAction(
    private val commands: LeaveCommandRepository,
    private val remote: LeaveActionRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
) : SyncTask {
    override val key = KEY
    private val execution = Mutex()

    suspend operator fun invoke(
        workspaceId: String,
        commandId: String,
    ): AppResult<LeaveCommandRecord> =
        withTimeoutOrNull(30.seconds) {
            execution.withLock {
                val access =
                    when (val result = currentAccess(workspaceId)) {
                        is AppResult.Failed -> return@withLock result
                        is AppResult.Success -> result.value
                    }
                val record =
                    when (val result = commands.find(access, commandId)) {
                        is AppResult.Failed -> return@withLock result
                        is AppResult.Success ->
                            result.value
                                ?: return@withLock leaveFailure(
                                    "client.leave_command_changed",
                                    FailureKind.Conflict,
                                )
                    }
                currentCoroutineContext().ensureActive()
                deliver(workspaceId, access, record)
            }
        } ?: leaveFailure("client.leave_timeout", FailureKind.Timeout)

    override suspend fun invoke(): SyncResult =
        withTimeoutOrNull(30.seconds) { execution.withLock { deliverBatch() } }
            ?: SyncResult.Retry(
                Failure(
                    FailureKind.Timeout,
                    "Leave delivery timed out.",
                    code = "client.leave_timeout",
                )
            )

    private suspend fun deliverBatch(): SyncResult {
        val session =
            when (val result = sessions.current()) {
                is AppResult.Failed -> return syncFailure(result.failure)
                is AppResult.Success -> result.value ?: return SyncResult.Complete
            }
        currentCoroutineContext().ensureActive()
        val workspace =
            when (val result = workspaces.current(session.id)) {
                is AppResult.Failed -> return syncFailure(result.failure)
                is AppResult.Success -> result.value ?: return SyncResult.Complete
            }
        val access =
            when (val result = currentAccess(workspace.id)) {
                is AppResult.Failed -> return syncFailure(result.failure)
                is AppResult.Success -> result.value
            }
        var cursor: String? = null
        var delivered = 0
        // The account command store admits at most 128 records; three 50-row pages cover it.
        repeat(3) {
            val page =
                when (val result = commands.page(access, cursor)) {
                    is AppResult.Failed -> return syncFailure(result.failure)
                    is AppResult.Success -> result.value
                }
            currentCoroutineContext().ensureActive()
            for (record in page.items) {
                if (
                    record.attempts >= LEAVE_AUTOMATIC_ATTEMPTS ||
                        record.outcome is LeaveCommandOutcome.Confirmed ||
                        record.outcome is LeaveCommandOutcome.Rejected
                )
                    continue
                when (val result = deliver(workspace.id, access, record)) {
                    is AppResult.Failed -> return syncFailure(result.failure)
                    is AppResult.Success ->
                        when (val outcome = result.value.outcome) {
                            is LeaveCommandOutcome.Unconfirmed ->
                                return outcome.failure?.let(::syncFailure) ?: SyncResult.Retry()
                            is LeaveCommandOutcome.Rejected ->
                                return SyncResult.Blocked(outcome.failure)
                            else -> Unit
                        }
                }
                delivered++
                if (delivered == 10) return SyncResult.Retry()
            }
            val next = page.nextCursor ?: return SyncResult.Complete
            if (next == cursor)
                return SyncResult.Blocked(
                    Failure(
                        FailureKind.Storage,
                        "Leave delivery progress is unavailable.",
                        code = "client.invalid_command_page",
                    )
                )
            cursor = next
        }
        return SyncResult.Complete
    }

    private suspend fun deliver(
        workspaceId: String,
        access: LeaveAccess,
        record: LeaveCommandRecord,
    ): AppResult<LeaveCommandRecord> {
        if (!record.command.belongsTo(access))
            return leaveFailure("client.leave_scope_changed", FailureKind.AccessDenied)
        val ownership = validateLeaveContinuation(access, currentAccess(workspaceId))
        if (ownership is AppResult.Failed) return ownership
        val permission = admitLeaveWrite(access)
        if (permission is AppResult.Failed) return permission
        if (
            record.outcome is LeaveCommandOutcome.Confirmed ||
                record.outcome is LeaveCommandOutcome.Rejected
        )
            return AppResult.Success(record)
        if (record.attempts >= LEAVE_MAXIMUM_ATTEMPTS)
            return leaveFailure("client.leave_retry_limit", FailureKind.Conflict)
        val policy =
            when (val result = currentPolicy(workspaceId, access)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        val attempt = record.attempts + 1
        when (
            val result = commands.update(access, record, LeaveCommandOutcome.Unconfirmed(), attempt)
        ) {
            is AppResult.Failed -> return result
            is AppResult.Success ->
                if (!result.value)
                    return leaveFailure("client.leave_command_changed", FailureKind.Conflict)
        }
        val sending =
            record.copy(
                revision = record.revision + 1,
                attempts = attempt,
                outcome = LeaveCommandOutcome.Unconfirmed(),
            )
        currentCoroutineContext().ensureActive()
        val continuation = validateLeaveContinuation(access, currentAccess(workspaceId))
        if (continuation is AppResult.Failed) return continuation
        val available = admitCompanyOperation(policy, build, CompanyModule.LEAVE, clock.now())
        if (available is AppResult.Failed) return available
        val response = remote.act(access.sessionId, record.command)
        currentCoroutineContext().ensureActive()
        val outcome =
            when (response) {
                is AppResult.Failed -> resolveLeaveCommandFailure(record.outcome, response.failure)
                is AppResult.Success ->
                    if (
                        response.value.id == record.command.requestId &&
                            response.value.version == record.command.expectedVersion + 1
                    )
                        LeaveCommandOutcome.Confirmed(response.value.version)
                    else
                        LeaveCommandOutcome.Unconfirmed(
                            Failure(
                                FailureKind.Unexpected,
                                "The leave response could not be read.",
                                code = "client.invalid_response",
                            )
                        )
            }
        // Retain evidence in its original account even when the initiating screen was replaced.
        when (val result = commands.update(access, sending, outcome)) {
            is AppResult.Failed -> return result
            is AppResult.Success ->
                if (!result.value)
                    return leaveFailure("client.leave_command_changed", FailureKind.Conflict)
        }
        currentCoroutineContext().ensureActive()
        val publication = validateLeaveContinuation(access, currentAccess(workspaceId))
        if (publication is AppResult.Failed) return publication
        return AppResult.Success(sending.copy(revision = sending.revision + 1, outcome = outcome))
    }

    private suspend fun currentPolicy(
        workspaceId: String,
        access: LeaveAccess,
    ): AppResult<CompanyPolicySnapshot> {
        val requestedAt = clock.now()
        val response = workspaces.clientPolicy(access.sessionId, access.companyId)
        currentCoroutineContext().ensureActive()
        val ownership = validateLeaveContinuation(access, currentAccess(workspaceId))
        if (ownership is AppResult.Failed) return ownership
        val snapshot =
            when (response) {
                is AppResult.Failed -> return response
                is AppResult.Success -> CompanyPolicySnapshot(response.value, requestedAt)
            }
        return when (
            val available = admitCompanyOperation(snapshot, build, CompanyModule.LEAVE, clock.now())
        ) {
            is AppResult.Failed -> available
            is AppResult.Success -> AppResult.Success(snapshot)
        }
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

    companion object {
        const val KEY = "hris-leave-commands"
    }
}
