@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.workforce.domain.overtime.working.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workforce.domain.entities.*
import dev.fajar.starter.workforce.domain.overtime.policies.*
import dev.fajar.starter.workforce.domain.overtime.repositories.OvertimeReplicaRepository
import dev.fajar.starter.workforce.domain.overtime.working.entities.*
import dev.fajar.starter.workforce.domain.overtime.working.policies.*
import dev.fajar.starter.workforce.domain.overtime.working.repositories.OvertimePlanWorkingCopyRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class SaveOvertimePlanWorkingCopy(
    private val copies: OvertimePlanWorkingCopyRepository,
    private val reads: OvertimeReplicaRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
) {

    suspend operator fun invoke(
        workspaceId: String,
        previous: OvertimePlanWorkingCopy,
        updated: OvertimePlanWorkingCopy,
    ): AppResult<OvertimePlanWorkingCopy> {
        if (
            validateOvertimePlanWorkingCopy(previous) is AppResult.Failed ||
                validateOvertimePlanWorkingCopy(updated) is AppResult.Failed ||
                previous.employeeId != updated.employeeId ||
                previous.revision == updated.revision
        )
            return overtimeFailure("client.overtime_working_copy_invalid")
        return withTimeoutOrNull(10.seconds) {
            val access =
                when (val result = currentAccess(workspaceId, updated.employeeId)) {
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
                validateOvertimeWorkingContinuation(
                    access,
                    currentAccess(workspaceId, updated.employeeId),
                )
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
        employeeId: String,
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
        val directory = reads.observeDirectory(access).first()
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
        val employment =
            when (directory) {
                is AppResult.Failed -> return directory
                is AppResult.Success ->
                    validateOvertimePlanWorkingOwner(access, employeeId, directory.value)
            }
        if (employment is AppResult.Failed) return employment
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
