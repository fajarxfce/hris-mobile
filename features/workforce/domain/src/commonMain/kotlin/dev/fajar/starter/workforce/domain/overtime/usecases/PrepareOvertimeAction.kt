@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.workforce.domain.overtime.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workforce.domain.entities.WorkforceAccess
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.domain.overtime.policies.*
import dev.fajar.starter.workforce.domain.overtime.repositories.*
import dev.fajar.starter.workforce.domain.overtime.working.policies.validateOvertimeActionWorkingSubmission
import dev.fajar.starter.workforce.domain.overtime.working.repositories.OvertimeActionWorkingCopyRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class PrepareOvertimeAction(
    private val commands: OvertimeCommandRepository,
    private val cache: OvertimeRequestReplicaRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
    private val copies: OvertimeActionWorkingCopyRepository,
    private val computation: CoroutineDispatcher = Dispatchers.Default,
) {
    suspend operator fun invoke(
        workspaceId: String,
        snapshot: OvertimeSnapshot<OvertimeRequestView>,
        actual: OvertimeLocalIntervalInput,
        reason: String,
        workingRevision: String? = null,
    ): AppResult<OvertimeCommandRecord> =
        withTimeoutOrNull(10.seconds) {
            val request = snapshot.value.details.request
            val resolved =
                withContext(computation) {
                    resolveOvertimeLocalInterval(actual, request.timezone, request.requested)
                }
            when (resolved) {
                is AppResult.Failed -> resolved
                is AppResult.Success ->
                    invoke(
                        workspaceId,
                        snapshot,
                        OvertimeRequestedAction.Actual(resolved.value),
                        reason,
                        workingRevision,
                    )
            }
        } ?: overtimeFailure("client.overtime_timeout", FailureKind.Timeout)

    suspend operator fun invoke(
        workspaceId: String,
        snapshot: OvertimeSnapshot<OvertimeRequestView>,
        action: OvertimeRequestedAction,
        reason: String,
        workingRevision: String? = null,
    ): AppResult<OvertimeCommandRecord> =
        withTimeoutOrNull(10.seconds) {
            val access =
                when (val result = currentAccess(workspaceId)) {
                    is AppResult.Failed -> return@withTimeoutOrNull result
                    is AppResult.Success -> result.value
                }
            val admitted = readContinuation(workspaceId, access)
            if (admitted is AppResult.Failed) return@withTimeoutOrNull admitted
            val payload =
                when (
                    val result =
                        validateOvertimeAction(snapshot, access, action, reason, clock.now())
                ) {
                    is AppResult.Failed -> return@withTimeoutOrNull result
                    is AppResult.Success ->
                        when (val input = result.value) {
                            is OvertimeCommandPayload.Actual ->
                                input.copy(workingRevision = workingRevision)
                            is OvertimeCommandPayload.Withdrawal ->
                                input.copy(workingRevision = workingRevision)
                            is OvertimeCommandPayload.Plan ->
                                return@withTimeoutOrNull overtimeFailure(
                                    "client.invalid_response",
                                    FailureKind.Unexpected,
                                )
                        }
                }
            val request = snapshot.value.details.request
            val working = validateWorkingCopy(workspaceId, access, snapshot, payload, reason)
            if (working is AppResult.Failed) return@withTimeoutOrNull working
            val slot = OvertimeCommandSlot.Request(request.id)
            val previous = commands.observe(access, slot).first()
            currentCoroutineContext().ensureActive()
            val ownership = readContinuation(workspaceId, access)
            if (ownership is AppResult.Failed) return@withTimeoutOrNull ownership
            val stillValid = validateOvertimeAction(snapshot, access, action, reason, clock.now())
            if (stillValid is AppResult.Failed) return@withTimeoutOrNull stillValid
            val existing =
                when (previous) {
                    is AppResult.Failed -> return@withTimeoutOrNull previous
                    is AppResult.Success -> previous.value
                }
            val normalized = reason.trim()
            if (existing != null)
                return@withTimeoutOrNull retained(existing, access, request, payload, normalized)
            val command =
                OvertimeCommand(
                    Uuid.random().toString(),
                    access.accountId,
                    access.audience,
                    access.companyId,
                    request.id,
                    request.employeeId,
                    request.employeeNumber,
                    snapshot.value.selection.employmentAfter,
                    request.workDate,
                    request.timezone,
                    payload,
                    normalized,
                    clock.now(),
                )
            val inserted = commands.insert(access, command)
            currentCoroutineContext().ensureActive()
            val continuation = readContinuation(workspaceId, access)
            if (continuation is AppResult.Failed) return@withTimeoutOrNull continuation
            when (inserted) {
                is AppResult.Failed -> return@withTimeoutOrNull inserted
                is AppResult.Success ->
                    when (val admission = admitOvertimeCommandInsertion(inserted.value)) {
                        is AppResult.Failed -> return@withTimeoutOrNull admission
                        is AppResult.Success -> Unit
                    }
            }
            val stored = commands.observe(access, slot).first()
            currentCoroutineContext().ensureActive()
            val publication = readContinuation(workspaceId, access)
            if (publication is AppResult.Failed) return@withTimeoutOrNull publication
            when (stored) {
                is AppResult.Failed -> stored
                is AppResult.Success -> retained(stored.value, access, request, payload, normalized)
            }
        } ?: overtimeFailure("client.overtime_timeout", FailureKind.Timeout)

    private suspend fun validateWorkingCopy(
        workspaceId: String,
        access: WorkforceAccess,
        snapshot: OvertimeSnapshot<OvertimeRequestView>,
        payload: OvertimeCommandPayload,
        reason: String,
    ): AppResult<Unit> {
        val revision = payload.workingRevision ?: return AppResult.Success(Unit)
        val kind =
            if (payload is OvertimeCommandPayload.Actual) OvertimeAction.SUBMIT_ACTUAL
            else OvertimeAction.WITHDRAW
        val observed = copies.observe(access, snapshot.value.selection, kind).first()
        currentCoroutineContext().ensureActive()
        val owner = readContinuation(workspaceId, access)
        if (owner is AppResult.Failed) return owner
        val stored =
            when (observed) {
                is AppResult.Failed -> return observed
                is AppResult.Success -> observed.value
            }
        return validateOvertimeActionWorkingSubmission(
            stored,
            revision,
            snapshot.value.details.request,
            payload,
            reason,
        )
    }

    private fun retained(
        record: OvertimeCommandRecord?,
        access: WorkforceAccess,
        request: OvertimeRequest,
        payload: OvertimeCommandPayload,
        reason: String,
    ): AppResult<OvertimeCommandRecord> {
        if (record != null && !record.command.belongsTo(access))
            return overtimeFailure("client.overtime_scope_changed", FailureKind.AccessDenied)
        return if (
            record != null &&
                record.command.requestId == request.id &&
                record.command.employeeId == request.employeeId &&
                record.command.workDate == request.workDate &&
                record.command.timezone == request.timezone &&
                record.command.payload == payload &&
                record.command.reason == reason
        )
            AppResult.Success(record)
        else overtimeFailure("client.overtime_command_pending", FailureKind.Conflict)
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
            val result = admitOvertime(workspaceId, workspace, latest, build, clock.now())
        ) {
            is AppResult.Failed -> result
            is AppResult.Success ->
                when (val allowed = admitSelfOvertimeCommands(result.value)) {
                    is AppResult.Failed -> allowed
                    is AppResult.Success -> result
                }
        }
    }
}
