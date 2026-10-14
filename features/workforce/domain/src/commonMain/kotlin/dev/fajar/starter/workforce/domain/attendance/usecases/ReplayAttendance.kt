package dev.fajar.starter.workforce.domain.attendance.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.sync.domain.*
import dev.fajar.starter.workforce.domain.attendance.entities.*
import dev.fajar.starter.workforce.domain.attendance.policies.*
import dev.fajar.starter.workforce.domain.attendance.repositories.*
import dev.fajar.starter.workforce.domain.entities.*
import dev.fajar.starter.workforce.domain.policies.*
import dev.fajar.starter.workforce.domain.replica.policies.validateStoredWorkforceReplica
import dev.fajar.starter.workforce.domain.replica.repositories.WorkforceReplicaRepository
import dev.fajar.starter.workspace.domain.entities.CompanyModule
import dev.fajar.starter.workspace.domain.entities.CompanyPolicySnapshot
import dev.fajar.starter.workspace.domain.policies.admitCompanyOperation
import dev.fajar.starter.workspace.domain.policies.workspaceFailure
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One application owner serializes manual retries and bounded background delivery. */
class ReplayAttendance(
    private val commands: AttendanceCommandRepository,
    private val remote: AttendanceRepository,
    private val replicas: WorkforceReplicaRepository,
    private val cache: AttendanceCacheRepository,
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
    ): AppResult<AttendanceCommandRecord> =
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
                            ?: return@withLock attendanceFailure(
                                "client.attendance_command_changed"
                            )
                }
            deliver(workspaceId, access, record)
        }

    override suspend fun invoke(): SyncResult =
        execution.withLock {
            withTimeoutOrNull(30.seconds) { deliverBatch() }
                ?: SyncResult.Retry(Failure(FailureKind.Timeout, "Attendance delivery timed out."))
        }

    private suspend fun deliverBatch(): SyncResult {
        val session =
            when (val result = sessions.current()) {
                is AppResult.Failed -> return syncFailure(result.failure)
                is AppResult.Success -> result.value ?: return SyncResult.Complete
            }
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
        repeat(3) {
            val page =
                when (val result = commands.page(access, cursor)) {
                    is AppResult.Failed -> return syncFailure(result.failure)
                    is AppResult.Success -> result.value
                }
            for (record in page.items) {
                if (record.outcome is AttendanceCommandOutcome.Confirmed) {
                    when (val result = retireConfirmed(workspace.id, access, record)) {
                        is AppResult.Failed -> return syncFailure(result.failure)
                        is AppResult.Success -> Unit
                    }
                    continue
                }
                if (
                    record.attempts >= ATTENDANCE_AUTOMATIC_ATTEMPTS ||
                        record.outcome is AttendanceCommandOutcome.Rejected
                )
                    continue
                when (val result = deliver(workspace.id, access, record)) {
                    is AppResult.Failed -> return syncFailure(result.failure)
                    is AppResult.Success ->
                        when (val outcome = result.value.outcome) {
                            is AttendanceCommandOutcome.Unconfirmed ->
                                return outcome.failure?.let(::syncFailure) ?: SyncResult.Retry()
                            is AttendanceCommandOutcome.Rejected ->
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
                        "Attendance delivery progress is unavailable.",
                        code = "client.invalid_command_page",
                    )
                )
            cursor = next
        }
        return SyncResult.Complete
    }

    private suspend fun retireConfirmed(
        workspaceId: String,
        access: WorkforceAccess,
        record: AttendanceCommandRecord,
    ): AppResult<Boolean> {
        val admission =
            withTimeoutOrNull(5.seconds) { replicas.admission(access) }
                ?: return attendanceFailure("client.attendance_timeout", FailureKind.Timeout)
        if (admission is AppResult.Failed) return admission
        (admission as AppResult.Success).value.denial?.let {
            return AppResult.Failed(it)
        }
        val facts =
            withTimeoutOrNull(5.seconds) {
                replicas
                    .observeFacts(
                        access,
                        record.command.employeeId,
                        CalendarPeriod(record.command.workDate, record.command.workDate),
                    )
                    .first()
            } ?: return attendanceFailure("client.attendance_timeout", FailureKind.Timeout)
        if (facts is AppResult.Failed) return facts
        val acquired = (facts as AppResult.Success).value
        if (acquired.checkpoint != null) {
            currentCoroutineContext().ensureActive()
            val continued = validateWorkforceContinuation(access, currentAccess(workspaceId))
            if (continued is AppResult.Failed) return continued
            val ownership = validateStoredWorkforceReplica(access, acquired.checkpoint)
            if (ownership is AppResult.Failed) return ownership
            val receipt = record.outcome as AttendanceCommandOutcome.Confirmed
            val present =
                acquired.attendance.any { day ->
                    day.day.entries.any {
                        it.id == record.command.id && it.version >= receipt.version
                    }
                }
            return if (present) commands.remove(access, record) else AppResult.Success(false)
        }
        val snapshot =
            when (val result = cache.observe(access).first()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value ?: return AppResult.Success(false)
            }
        currentCoroutineContext().ensureActive()
        val continued = validateWorkforceContinuation(access, currentAccess(workspaceId))
        if (continued is AppResult.Failed) return continued
        if (validateAttendanceCaptureSnapshot(access, snapshot, clock.now()) is AppResult.Failed)
            return AppResult.Success(false)
        if (snapshot.employeeId != record.command.employeeId) return AppResult.Success(false)
        if (
            record.command.workDate >= snapshot.period.from &&
                snapshot.days.none { day -> day.entries.any { it.id == record.command.id } }
        )
            return AppResult.Success(false)
        return commands.remove(access, record)
    }

    private suspend fun deliver(
        workspaceId: String,
        access: WorkforceAccess,
        record: AttendanceCommandRecord,
    ): AppResult<AttendanceCommandRecord> {
        if (!record.command.belongsTo(access))
            return attendanceFailure("client.attendance_scope_changed", FailureKind.AccessDenied)
        when (val current = validateWorkforceContinuation(access, currentAccess(workspaceId))) {
            is AppResult.Failed -> return current
            is AppResult.Success -> Unit
        }
        if (record.outcome is AttendanceCommandOutcome.Confirmed) return AppResult.Success(record)
        if (record.attempts >= ATTENDANCE_MAXIMUM_ATTEMPTS)
            return attendanceFailure("client.attendance_retry_limit")
        val policyRequestedAt = clock.now()
        val policyResponse = workspaces.clientPolicy(access.sessionId, access.companyId)
        currentCoroutineContext().ensureActive()
        when (val current = validateWorkforceContinuation(access, currentAccess(workspaceId))) {
            is AppResult.Failed -> return current
            is AppResult.Success -> Unit
        }
        val policy =
            when (policyResponse) {
                is AppResult.Failed -> {
                    if (calendarFailureInvalidatesCache(policyResponse.failure)) {
                        withTimeoutOrNull(2.seconds) {
                            replicas.restrict(access, policyResponse.failure)
                        }
                        withTimeoutOrNull(2.seconds) { replicas.clear(access) }
                    }
                    return policyResponse
                }
                is AppResult.Success ->
                    CompanyPolicySnapshot(policyResponse.value, policyRequestedAt)
            }
        val availability =
            admitCompanyOperation(policy, build, CompanyModule.WORKFORCE, clock.now())
        if (availability is AppResult.Failed) return availability
        val attempt = record.attempts + 1
        when (
            val saved =
                commands.update(access, record, AttendanceCommandOutcome.Unconfirmed(), attempt)
        ) {
            is AppResult.Failed -> return saved
            is AppResult.Success ->
                if (!saved.value) return attendanceFailure("client.attendance_command_changed")
        }
        val sending =
            record.copy(
                revision = record.revision + 1,
                attempts = attempt,
                outcome = AttendanceCommandOutcome.Unconfirmed(),
            )
        currentCoroutineContext().ensureActive()
        when (val current = validateWorkforceContinuation(access, currentAccess(workspaceId))) {
            is AppResult.Failed -> return current
            is AppResult.Success -> Unit
        }
        val stillAvailable =
            admitCompanyOperation(policy, build, CompanyModule.WORKFORCE, clock.now())
        if (stillAvailable is AppResult.Failed) return stillAvailable
        val result = remote.record(access, record.command)
        currentCoroutineContext().ensureActive()
        val outcome =
            when (result) {
                is AppResult.Failed ->
                    resolveAttendanceCommandFailure(record.outcome, result.failure)
                is AppResult.Success -> {
                    if (result.value.id != record.command.id || result.value.version != 0L)
                        AttendanceCommandOutcome.Unconfirmed(
                            Failure(
                                FailureKind.Unexpected,
                                "The attendance response could not be read.",
                                code = "client.invalid_response",
                            )
                        )
                    else AttendanceCommandOutcome.Confirmed(result.value.version)
                }
            }
        // A receipt belongs to this retained account command even if its screen has changed.
        when (val saved = commands.update(access, sending, outcome)) {
            is AppResult.Failed -> return saved
            is AppResult.Success ->
                if (!saved.value) return attendanceFailure("client.attendance_command_changed")
        }
        currentCoroutineContext().ensureActive()
        when (val current = validateWorkforceContinuation(access, currentAccess(workspaceId))) {
            is AppResult.Failed -> return current
            is AppResult.Success -> Unit
        }
        return AppResult.Success(sending.copy(revision = sending.revision + 1, outcome = outcome))
    }

    private suspend fun currentAccess(workspaceId: String): AppResult<WorkforceAccess> {
        val session =
            when (val result = sessions.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success ->
                    result.value
                        ?: return workspaceFailure(
                            "client.session_changed",
                            FailureKind.Unauthorized,
                        )
            }
        val workspace =
            when (val result = workspaces.current(session.id)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        return admitWorkforce(workspaceId, workspace, session, build, clock.now())
    }

    companion object {
        const val KEY = "hris-attendance-commands"
    }
}
