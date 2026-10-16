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
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class OpenOvertimeActionWorkingCopy(
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
        action: OvertimeAction,
    ): AppResult<OvertimeActionWorkingCopy> {
        val valid = validateOvertimeActionWorkingTarget(selection, action)
        if (valid is AppResult.Failed) return valid
        return withTimeoutOrNull(10.seconds) {
            val access =
                when (val result = currentAccess(workspaceId, selection)) {
                    is AppResult.Failed -> return@withTimeoutOrNull result
                    is AppResult.Success -> result.value
                }
            val observed = copies.observe(access, selection, action).first()
            currentCoroutineContext().ensureActive()
            val owner =
                validateOvertimeWorkingContinuation(access, currentAccess(workspaceId, selection))
            if (owner is AppResult.Failed) return@withTimeoutOrNull owner
            val saved =
                when (observed) {
                    is AppResult.Failed -> return@withTimeoutOrNull observed
                    is AppResult.Success -> observed.value
                }
            if (saved != null) {
                val stored = validateOvertimeActionWorkingCopy(saved)
                if (stored is AppResult.Failed) return@withTimeoutOrNull stored
                if (
                    saved.requestId != selection.requestId ||
                        saved.employeeId != selection.employeeId ||
                        saved.action != action
                )
                    return@withTimeoutOrNull overtimeFailure(
                        "client.overtime_scope_changed",
                        FailureKind.AccessDenied,
                    )
                return@withTimeoutOrNull AppResult.Success(saved)
            }
            val facts = reads.observeRequest(access, selection).first()
            currentCoroutineContext().ensureActive()
            val initialOwner =
                validateOvertimeWorkingContinuation(access, currentAccess(workspaceId, selection))
            if (initialOwner is AppResult.Failed) return@withTimeoutOrNull initialOwner
            val request =
                when (facts) {
                    is AppResult.Failed -> return@withTimeoutOrNull facts
                    is AppResult.Success ->
                        when (
                            val result =
                                validateOvertimeActionWorkingOwner(access, selection, facts.value)
                        ) {
                            is AppResult.Failed -> return@withTimeoutOrNull result
                            is AppResult.Success -> result.value
                        }
                }
            val initial =
                OvertimeActionWorkingCopy(
                    selection.requestId,
                    selection.employeeId,
                    action,
                    initialOvertimeActionWorkingInput(action, request),
                    Uuid.random().toString(),
                    clock.now(),
                )
            val inserted = copies.save(access, null, initial)
            currentCoroutineContext().ensureActive()
            val publication =
                validateOvertimeWorkingContinuation(access, currentAccess(workspaceId, selection))
            if (publication is AppResult.Failed) return@withTimeoutOrNull publication
            when (inserted) {
                is AppResult.Failed -> inserted
                is AppResult.Success ->
                    inserted.value?.let { AppResult.Success(it) }
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
