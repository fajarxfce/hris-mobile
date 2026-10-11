package dev.fajar.starter.workforce.domain.attendance.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workforce.domain.attendance.entities.AttendanceCommandOutcome
import dev.fajar.starter.workforce.domain.attendance.policies.attendanceFailure
import dev.fajar.starter.workforce.domain.attendance.repositories.AttendanceCommandRepository
import dev.fajar.starter.workforce.domain.policies.*
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Explicitly dismiss only a proven rejection. CAS cannot erase an in-flight retry. */
class DismissAttendanceRejection(
    private val commands: AttendanceCommandRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
) {
    suspend operator fun invoke(workspaceId: String, id: String, revision: Long): AppResult<Unit> {
        val access =
            when (val result = currentAccess(workspaceId)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        val record =
            when (val result = commands.find(access, id)) {
                is AppResult.Failed -> return result
                is AppResult.Success ->
                    result.value ?: return attendanceFailure("client.attendance_changed")
            }
        if (record.revision != revision || record.outcome !is AttendanceCommandOutcome.Rejected)
            return attendanceFailure("client.attendance_changed", FailureKind.Conflict)
        currentCoroutineContext().ensureActive()
        val continued = validateWorkforceContinuation(access, currentAccess(workspaceId))
        if (continued is AppResult.Failed) return continued
        return when (val removed = commands.remove(access, record)) {
            is AppResult.Failed -> removed
            is AppResult.Success ->
                if (removed.value) AppResult.Success(Unit)
                else attendanceFailure("client.attendance_changed", FailureKind.Conflict)
        }
    }

    private suspend fun currentAccess(
        workspaceId: String
    ): AppResult<dev.fajar.starter.workforce.domain.entities.WorkforceAccess> {
        val session =
            when (val result = sessions.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success ->
                    result.value
                        ?: return attendanceFailure("session_revoked", FailureKind.Unauthorized)
            }
        val workspace =
            when (val result = workspaces.current(session.id)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        return admitWorkforce(workspaceId, workspace, session, build, clock.now())
    }
}
