@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.workforce.domain.overtime.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.sync.domain.*
import dev.fajar.starter.workforce.domain.entities.WorkforceAccess
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.domain.overtime.policies.*
import dev.fajar.starter.workforce.domain.overtime.repositories.*
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

/** Serializes manual/background delivery; every attempt reuses the retained operation and body. */
class ReplayOvertimeCommands(
    private val commands: OvertimeCommandRepository,
    private val remote: OvertimeSubmissionRepository,
    private val cache: OvertimeRequestReplicaRepository,
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
    ): AppResult<OvertimeCommandRecord> =
        withTimeoutOrNull(30.seconds) {
            if (Uuid.parseOrNull(commandId)?.toString() != commandId)
                return@withTimeoutOrNull overtimeFailure(
                    "client.overtime_command_changed",
                    FailureKind.Conflict,
                )
            execution.withLock {
                val access =
                    when (val result = currentAccess(workspaceId)) {
                        is AppResult.Failed -> return@withLock result
                        is AppResult.Success -> result.value
                    }
                val admitted = readContinuation(workspaceId, access)
                if (admitted is AppResult.Failed) return@withLock admitted
                val stored = commands.find(access, commandId)
                currentCoroutineContext().ensureActive()
                val ownership = readContinuation(workspaceId, access)
                if (ownership is AppResult.Failed) return@withLock ownership
                val record =
                    when (stored) {
                        is AppResult.Failed -> return@withLock stored
                        is AppResult.Success ->
                            stored.value
                                ?: return@withLock overtimeFailure(
                                    "client.overtime_command_changed",
                                    FailureKind.Conflict,
                                )
                    }
                currentCoroutineContext().ensureActive()
                deliver(workspaceId, access, record)
            }
        } ?: overtimeFailure("client.overtime_timeout", FailureKind.Timeout)

    override suspend fun invoke(): SyncResult =
        withTimeoutOrNull(30.seconds) { execution.withLock { deliverBatch() } }
            ?: SyncResult.Retry(
                Failure(
                    FailureKind.Timeout,
                    "Overtime delivery timed out.",
                    code = "client.overtime_timeout",
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
        val admitted = readContinuation(workspace.id, access)
        if (admitted is AppResult.Failed) return syncFailure(admitted.failure)
        var cursor: String? = null
        var delivered = 0
        // The account command store admits at most 128 records; three 50-row pages cover it.
        repeat(3) {
            val records = commands.page(access, cursor)
            currentCoroutineContext().ensureActive()
            val ownership = readContinuation(workspace.id, access)
            if (ownership is AppResult.Failed) return syncFailure(ownership.failure)
            val page =
                when (records) {
                    is AppResult.Failed -> return syncFailure(records.failure)
                    is AppResult.Success -> records.value
                }
            if (page.items.size > 50)
                return SyncResult.Blocked(
                    Failure(
                        FailureKind.Storage,
                        "Overtime delivery progress is unavailable.",
                        code = "client.invalid_command_page",
                    )
                )
            for (record in page.items) {
                if (
                    record.attempts >= OVERTIME_AUTOMATIC_ATTEMPTS ||
                        record.outcome is OvertimeCommandOutcome.Confirmed ||
                        record.outcome is OvertimeCommandOutcome.Rejected
                )
                    continue
                when (val result = deliver(workspace.id, access, record)) {
                    is AppResult.Failed -> return syncFailure(result.failure)
                    is AppResult.Success ->
                        when (val outcome = result.value.outcome) {
                            is OvertimeCommandOutcome.Unconfirmed ->
                                return outcome.failure?.let(::syncFailure) ?: SyncResult.Retry()
                            is OvertimeCommandOutcome.Rejected ->
                                return SyncResult.Blocked(outcome.failure)
                            else -> Unit
                        }
                }
                delivered++
                if (delivered == 10) return SyncResult.Retry()
            }
            val next = page.nextCursor ?: return SyncResult.Complete
            if (cursor != null && next <= cursor)
                return SyncResult.Blocked(
                    Failure(
                        FailureKind.Storage,
                        "Overtime delivery progress is unavailable.",
                        code = "client.invalid_command_page",
                    )
                )
            cursor = next
        }
        return SyncResult.Retry()
    }

    private suspend fun deliver(
        workspaceId: String,
        access: WorkforceAccess,
        record: OvertimeCommandRecord,
    ): AppResult<OvertimeCommandRecord> {
        if (!record.command.belongsTo(access))
            return overtimeFailure("client.overtime_scope_changed", FailureKind.AccessDenied)
        val ownership = readContinuation(workspaceId, access)
        if (ownership is AppResult.Failed) return ownership
        val permission = admitSelfOvertimeCommands(access)
        if (permission is AppResult.Failed) return permission
        if (
            record.outcome is OvertimeCommandOutcome.Confirmed ||
                record.outcome is OvertimeCommandOutcome.Rejected
        )
            return AppResult.Success(record)
        if (record.attempts >= OVERTIME_MAXIMUM_ATTEMPTS)
            return overtimeFailure("client.overtime_retry_limit", FailureKind.Conflict)
        val policy =
            when (val result = currentPolicy(workspaceId, access)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        val attempt = record.attempts + 1
        when (
            val result =
                commands.update(access, record, OvertimeCommandOutcome.Unconfirmed(), attempt)
        ) {
            is AppResult.Failed -> return result
            is AppResult.Success ->
                if (!result.value)
                    return overtimeFailure("client.overtime_command_changed", FailureKind.Conflict)
        }
        val sending =
            record.copy(
                revision = record.revision + 1,
                attempts = attempt,
                outcome = OvertimeCommandOutcome.Unconfirmed(),
            )
        currentCoroutineContext().ensureActive()
        val continuation = readContinuation(workspaceId, access)
        if (continuation is AppResult.Failed) return continuation
        val available = admitCompanyOperation(policy, build, CompanyModule.WORKFORCE, clock.now())
        if (available is AppResult.Failed) return reject(access, available)
        val response = remote.send(access.sessionId, record.command)
        currentCoroutineContext().ensureActive()
        if (response is AppResult.Failed) {
            val owner = validateOvertimeContinuation(access, currentAccess(workspaceId))
            if (owner is AppResult.Success) reject(access, response)
        }
        val outcome =
            when (response) {
                is AppResult.Failed ->
                    resolveOvertimeCommandFailure(record.outcome, response.failure)
                is AppResult.Success ->
                    if (
                        response.value.id == record.command.requestId &&
                            response.value.version == record.command.expectedReceiptVersion()
                    )
                        OvertimeCommandOutcome.Confirmed(response.value.version)
                    else
                        OvertimeCommandOutcome.Unconfirmed(
                            Failure(
                                FailureKind.Unexpected,
                                "The overtime response could not be read.",
                                code = "client.invalid_response",
                            )
                        )
            }
        // Retain evidence in its original account even when the initiating screen was replaced.
        when (val result = commands.update(access, sending, outcome)) {
            is AppResult.Failed -> return result
            is AppResult.Success ->
                if (!result.value)
                    return overtimeFailure("client.overtime_command_changed", FailureKind.Conflict)
        }
        currentCoroutineContext().ensureActive()
        val publication = readContinuation(workspaceId, access)
        if (publication is AppResult.Failed) return publication
        return AppResult.Success(sending.copy(revision = sending.revision + 1, outcome = outcome))
    }

    private suspend fun currentPolicy(
        workspaceId: String,
        access: WorkforceAccess,
    ): AppResult<CompanyPolicySnapshot> {
        val requestedAt = clock.now()
        val response = workspaces.clientPolicy(access.sessionId, access.companyId)
        currentCoroutineContext().ensureActive()
        val ownership = readContinuation(workspaceId, access)
        if (ownership is AppResult.Failed) return ownership
        val snapshot =
            when (response) {
                is AppResult.Failed -> return reject(access, response)
                is AppResult.Success -> CompanyPolicySnapshot(response.value, requestedAt)
            }
        return when (
            val available =
                admitCompanyOperation(snapshot, build, CompanyModule.WORKFORCE, clock.now())
        ) {
            is AppResult.Failed -> reject(access, available)
            is AppResult.Success -> AppResult.Success(snapshot)
        }
    }

    private suspend fun reject(
        access: WorkforceAccess,
        failure: AppResult.Failed,
    ): AppResult.Failed {
        if (overtimeFailureInvalidatesCache(failure.failure)) {
            withTimeoutOrNull(2.seconds) { cache.restrict(access, failure.failure) }
        }
        return failure
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
            val admitted = admitOvertime(workspaceId, workspace, latest, build, clock.now())
        ) {
            is AppResult.Failed -> admitted
            is AppResult.Success ->
                when (val permitted = admitSelfOvertimeCommands(admitted.value)) {
                    is AppResult.Failed -> permitted
                    is AppResult.Success -> admitted
                }
        }
    }

    companion object {
        const val KEY = "hris-overtime-commands"
    }
}
