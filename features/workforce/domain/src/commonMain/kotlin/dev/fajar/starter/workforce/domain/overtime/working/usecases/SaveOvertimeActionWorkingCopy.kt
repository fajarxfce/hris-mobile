@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.workforce.domain.overtime.working.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workforce.domain.entities.*
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.domain.overtime.policies.*
import dev.fajar.starter.workforce.domain.overtime.repositories.OvertimeRequestReplicaRepository
import dev.fajar.starter.workforce.domain.overtime.working.entities.*
import dev.fajar.starter.workforce.domain.overtime.working.policies.*
import dev.fajar.starter.workforce.domain.overtime.working.repositories.OvertimeActionWorkingCopyRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class SaveOvertimeActionWorkingCopy(
    private val copies: OvertimeActionWorkingCopyRepository,
    private val reads: OvertimeRequestReplicaRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
) {

    suspend operator fun invoke(
        workspaceId: String,
        selection: OvertimeRequestSelection,
        previous: OvertimeActionWorkingCopy,
        updated: OvertimeActionWorkingCopy,
    ): AppResult<OvertimeActionWorkingCopy> {
        if (
            validateOvertimeActionWorkingTarget(selection, updated.action) is AppResult.Failed ||
                validateOvertimeActionWorkingCopy(previous) is AppResult.Failed ||
                validateOvertimeActionWorkingCopy(updated) is AppResult.Failed ||
                previous.employeeId != selection.employeeId ||
                previous.requestId != selection.requestId ||
                previous.employeeId != updated.employeeId ||
                previous.requestId != updated.requestId ||
                previous.action != updated.action ||
                previous.revision == updated.revision ||
                previous.input.requestVersion != updated.input.requestVersion
        )
            return overtimeFailure("client.overtime_working_copy_invalid")
        return withTimeoutOrNull(10.seconds) {
            val access =
                when (val result = currentAccess(workspaceId, selection)) {
                    is AppResult.Failed -> return@withTimeoutOrNull result
                    is AppResult.Success -> result.value
                }
            val saved =
                copies.save(
                    access,
                    previous.revision,
                    updated.copy(
                        savedAt = clock.now(),
                        hasEdits = previous.hasEdits || previous.input != updated.input,
                    ),
                )
            currentCoroutineContext().ensureActive()
            val owner =
                validateOvertimeWorkingContinuation(access, currentAccess(workspaceId, selection))
            if (owner is AppResult.Failed) return@withTimeoutOrNull owner
            when (saved) {
                is AppResult.Failed -> saved
                is AppResult.Success ->
                    saved.value?.let { AppResult.Success(it) }
                        ?: overtimeFailure(
                            "client.overtime_working_copy_changed",
                            FailureKind.Conflict,
                        )
            }
        } ?: overtimeFailure("client.overtime_timeout", FailureKind.Timeout)
    }

    private suspend fun currentAccess(
        workspaceId: String,
        selection: OvertimeRequestSelection,
    ): AppResult<WorkforceAccess> {
        val access =
            when (val result = currentScope(workspaceId)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        val permitted = admitSelfOvertimeCommands(access)
        if (permitted is AppResult.Failed) return permitted
        val admission = reads.admission(access)
        currentCoroutineContext().ensureActive()
        val owner = validateOvertimeWorkingContinuation(access, currentScope(workspaceId))
        if (owner is AppResult.Failed) return owner
        when (admission) {
            is AppResult.Failed -> return admission
            is AppResult.Success ->
                admission.value.denial?.let {
                    return AppResult.Failed(it)
                }
        }
        val observed = reads.observeRequest(access, selection).first()
        currentCoroutineContext().ensureActive()
        val latestAdmission = reads.admission(access)
        currentCoroutineContext().ensureActive()
        val current = validateOvertimeWorkingContinuation(access, currentScope(workspaceId))
        if (current is AppResult.Failed) return current
        when (latestAdmission) {
            is AppResult.Failed -> return latestAdmission
            is AppResult.Success ->
                latestAdmission.value.denial?.let {
                    return AppResult.Failed(it)
                }
        }
        val request =
            when (observed) {
                is AppResult.Failed -> return observed
                is AppResult.Success ->
                    validateOvertimeActionWorkingOwner(access, selection, observed.value)
            }
        if (request is AppResult.Failed) return request
        return current
    }

    private suspend fun currentScope(workspaceId: String): AppResult<WorkforceAccess> {
        val session =
            when (val result = sessions.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success ->
                    result.value
                        ?: return overtimeFailure("session_revoked", FailureKind.Unauthorized)
            }
        val workspace =
            when (val result = workspaces.current(session.id)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        val latest =
            when (val result = sessions.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        currentCoroutineContext().ensureActive()
        return admitOvertime(workspaceId, workspace, latest, build, clock.now())
    }
}
