@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.workforce.domain.attendance.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.location.domain.entities.*
import dev.fajar.starter.location.domain.repositories.LocationRepository
import dev.fajar.starter.workforce.domain.attendance.entities.*
import dev.fajar.starter.workforce.domain.attendance.policies.*
import dev.fajar.starter.workforce.domain.attendance.repositories.*
import dev.fajar.starter.workforce.domain.entities.WorkforceAccess
import dev.fajar.starter.workforce.domain.policies.*
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Owns permission policy and saves immutable evidence before attendance transport. */
class CaptureAttendance(
    private val remote: AttendanceRepository,
    private val cache: AttendanceCacheRepository,
    private val commands: AttendanceCommandRepository,
    private val location: LocationRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
) {
    private val execution = Mutex()

    suspend operator fun invoke(
        workspaceId: String,
        employeeId: String,
        kind: AttendanceKind,
        withoutLocation: Boolean = false,
    ): AppResult<AttendanceCommandRecord> =
        execution.withLock {
            val access =
                when (val result = currentAccess(workspaceId)) {
                    is AppResult.Failed -> return@withLock result
                    is AppResult.Success -> result.value
                }
            val snapshot =
                when (val result = cache.observe(access).first()) {
                    is AppResult.Failed -> return@withLock result
                    is AppResult.Success -> result.value
                }
            val date =
                when (
                    val result =
                        validateAttendanceCapture(access, snapshot, employeeId, clock.now())
                ) {
                    is AppResult.Failed -> return@withLock result
                    is AppResult.Success -> result.value
                }
            val resource = "$employeeId/$date/${kind.name}"
            val retained =
                when (val result = commands.observe(access, resource).first()) {
                    is AppResult.Failed -> return@withLock result
                    is AppResult.Success -> result.value.singleOrNull()
                }
            val initialOwner = validateWorkforceContinuation(access, currentAccess(workspaceId))
            if (initialOwner is AppResult.Failed) return@withLock initialOwner
            if (retained != null) return@withLock AppResult.Success(retained)
            if (
                snapshot?.days?.any {
                    it.date == date &&
                        it.entries.any { entry ->
                            entry.kind == kind && entry.status != AttendanceStatus.REJECTED
                        }
                } == true
            )
                return@withLock attendanceFailure("client.attendance_already_recorded")

            val fix =
                when (val result = acquireLocation(workspaceId, access, withoutLocation)) {
                    is AppResult.Failed -> return@withLock result
                    is AppResult.Success -> result.value
                }
            currentCoroutineContext().ensureActive()
            val freshOwner = validateWorkforceContinuation(access, currentAccess(workspaceId))
            if (freshOwner is AppResult.Failed) return@withLock freshOwner
            val capturedAt = clock.now()
            if (fix != null) {
                val valid = validateAttendanceLocation(fix, capturedAt)
                if (valid is AppResult.Failed) return@withLock valid
            }
            val dateCheck = validateAttendanceCapture(access, snapshot, employeeId, capturedAt)
            if (dateCheck is AppResult.Failed) return@withLock dateCheck
            if ((dateCheck as AppResult.Success).value != date)
                return@withLock attendanceFailure("client.attendance_refresh_required")
            // Native session identity is an opaque installation-session label, not hardware
            // attestation.
            val window =
                when (val result = captureWindow(access, employeeId, withoutLocation)) {
                    is AppResult.Failed -> return@withLock result
                    is AppResult.Success -> result.value
                }
            currentCoroutineContext().ensureActive()
            val finalOwner = validateWorkforceContinuation(access, currentAccess(workspaceId))
            if (finalOwner is AppResult.Failed) return@withLock finalOwner
            val valid =
                validateAttendanceCache(
                    (finalOwner as AppResult.Success).value,
                    snapshot,
                    clock.now(),
                )
            if (valid is AppResult.Failed) return@withLock valid
            val command =
                AttendanceCommand(
                    Uuid.random().toString(),
                    access.accountId,
                    access.audience,
                    access.companyId,
                    employeeId,
                    date,
                    kind,
                    capturedAt,
                    access.sessionId,
                    window?.id,
                    window == null,
                    fix,
                )
            retain(workspaceId, access, command)
        }

    private suspend fun acquireLocation(
        workspaceId: String,
        access: WorkforceAccess,
        withoutLocation: Boolean,
    ): AppResult<LocationFix?> {
        if (withoutLocation) return AppResult.Success(null)
        val observed =
            when (val result = location.access()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        val permitted =
            if (observed.permission == LocationPermission.Denied) {
                when (val result = location.requestPermission()) {
                    is AppResult.Failed -> return result
                    is AppResult.Success -> result.value
                }
            } else observed
        when (permitted.permission) {
            LocationPermission.PermanentlyDenied ->
                return attendanceFailure(
                    "client.location_permanently_denied",
                    FailureKind.Permission,
                )
            LocationPermission.Denied ->
                return attendanceFailure("client.location_permission", FailureKind.Permission)
            LocationPermission.Unavailable ->
                return attendanceFailure("client.location_unavailable", FailureKind.Unavailable)
            LocationPermission.Granted -> Unit
        }
        if (!permitted.servicesEnabled)
            return attendanceFailure("client.location_disabled", FailureKind.Unavailable)
        val continued = validateWorkforceContinuation(access, currentAccess(workspaceId))
        if (continued is AppResult.Failed) return continued
        return withTimeoutOrNull(15.seconds) { location.current() }
            ?: attendanceFailure("client.location_timeout", FailureKind.Timeout)
    }

    private suspend fun captureWindow(
        access: WorkforceAccess,
        employeeId: String,
        withoutLocation: Boolean,
    ): AppResult<AttendanceCaptureWindow?> {
        if (withoutLocation) return AppResult.Success(null)
        val result =
            withTimeoutOrNull(10.seconds) {
                remote.window(access, employeeId, access.sessionId, Uuid.random().toString())
            }
        return when (result) {
            null -> AppResult.Success(null)
            is AppResult.Failed ->
                if (result.failure.kind in setOf(FailureKind.Network, FailureKind.Timeout))
                    AppResult.Success(null)
                else result
            is AppResult.Success -> result
        }
    }

    private suspend fun retain(
        workspaceId: String,
        access: WorkforceAccess,
        command: AttendanceCommand,
    ): AppResult<AttendanceCommandRecord> {
        when (val result = commands.insert(access, command)) {
            is AppResult.Failed -> return result
            is AppResult.Success ->
                when (result.value) {
                    AttendanceCommandAdmission.CAPACITY_EXCEEDED ->
                        return attendanceFailure("client.command_queue_full", FailureKind.Storage)
                    AttendanceCommandAdmission.SCOPE_CHANGED ->
                        return attendanceFailure(
                            "client.attendance_scope_changed",
                            FailureKind.AccessDenied,
                        )
                    AttendanceCommandAdmission.STORED,
                    AttendanceCommandAdmission.OCCUPIED -> Unit
                }
        }
        currentCoroutineContext().ensureActive()
        val saved = commands.observe(access, command.resourceId).first()
        currentCoroutineContext().ensureActive()
        val publication = validateWorkforceContinuation(access, currentAccess(workspaceId))
        if (publication is AppResult.Failed) return publication
        return when (saved) {
            is AppResult.Failed -> saved
            is AppResult.Success ->
                saved.value.singleOrNull()?.let { AppResult.Success(it) }
                    ?: attendanceFailure("client.attendance_changed", FailureKind.Conflict)
        }
    }

    private suspend fun currentAccess(workspaceId: String): AppResult<WorkforceAccess> {
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
