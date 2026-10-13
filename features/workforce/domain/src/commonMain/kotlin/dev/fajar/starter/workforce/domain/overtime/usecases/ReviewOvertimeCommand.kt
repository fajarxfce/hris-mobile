@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.workforce.domain.overtime.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workforce.domain.entities.WorkforceAccess
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.domain.overtime.policies.*
import dev.fajar.starter.workforce.domain.overtime.repositories.*
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/**
 * Discards unsent/rejected input; a confirmed command first needs admitted updated request facts.
 */
class ReviewOvertimeCommand(
    private val commands: OvertimeCommandRepository,
    private val cache: OvertimeCacheRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
) {
    suspend operator fun invoke(workspaceId: String, commandId: String): AppResult<Boolean> =
        withTimeoutOrNull(10.seconds) {
            if (Uuid.parseOrNull(commandId)?.toString() != commandId)
                return@withTimeoutOrNull overtimeFailure(
                    "client.overtime_command_changed",
                    FailureKind.Conflict,
                )
            val access =
                when (val result = currentAccess(workspaceId)) {
                    is AppResult.Failed -> return@withTimeoutOrNull result
                    is AppResult.Success -> result.value
                }
            val stored = commands.find(access, commandId)
            currentCoroutineContext().ensureActive()
            val ownership = validateOvertimeContinuation(access, currentAccess(workspaceId))
            if (ownership is AppResult.Failed) return@withTimeoutOrNull ownership
            val record =
                when (stored) {
                    is AppResult.Failed -> return@withTimeoutOrNull stored
                    is AppResult.Success ->
                        stored.value ?: return@withTimeoutOrNull AppResult.Success(false)
                }
            if (!record.command.belongsTo(access))
                return@withTimeoutOrNull overtimeFailure(
                    "client.overtime_scope_changed",
                    FailureKind.AccessDenied,
                )
            if (record.outcome is OvertimeCommandOutcome.Unconfirmed)
                return@withTimeoutOrNull overtimeFailure(
                    "client.overtime_unconfirmed",
                    FailureKind.Conflict,
                )
            if (record.outcome is OvertimeCommandOutcome.Confirmed) {
                val observed = cache.observeRequest(access).first()
                currentCoroutineContext().ensureActive()
                val continuation = validateOvertimeContinuation(access, currentAccess(workspaceId))
                if (continuation is AppResult.Failed) return@withTimeoutOrNull continuation
                val snapshot =
                    when (observed) {
                        is AppResult.Failed -> return@withTimeoutOrNull observed
                        is AppResult.Success -> observed.value
                    }
                val lease = validateOvertimeActionSnapshot(access, snapshot, clock.now())
                if (lease is AppResult.Failed) return@withTimeoutOrNull lease
                if (snapshot == null)
                    return@withTimeoutOrNull overtimeFailure(
                        "client.overtime_updates_pending",
                        FailureKind.Conflict,
                    )
                val request = snapshot.value.details.request
                if (
                    request.id != record.command.requestId ||
                        request.employeeId != record.command.employeeId ||
                        request.version < record.outcome.version ||
                        snapshot.value.selection.requestId != request.id ||
                        snapshot.value.selection.employeeId != request.employeeId
                )
                    return@withTimeoutOrNull overtimeFailure(
                        "client.overtime_updates_pending",
                        FailureKind.Conflict,
                    )
            }
            val removal = commands.remove(access, record)
            currentCoroutineContext().ensureActive()
            val publication = validateOvertimeContinuation(access, currentAccess(workspaceId))
            if (publication is AppResult.Failed) publication else removal
        } ?: overtimeFailure("client.overtime_timeout", FailureKind.Timeout)

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
