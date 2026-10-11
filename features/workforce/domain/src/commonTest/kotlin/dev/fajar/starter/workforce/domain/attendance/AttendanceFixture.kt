package dev.fajar.starter.workforce.domain.attendance

import dev.fajar.starter.common.result.*
import dev.fajar.starter.location.domain.entities.*
import dev.fajar.starter.location.domain.repositories.LocationRepository
import dev.fajar.starter.workforce.domain.WorkCalendarFixture
import dev.fajar.starter.workforce.domain.attendance.entities.*
import dev.fajar.starter.workforce.domain.attendance.repositories.*
import dev.fajar.starter.workforce.domain.attendance.usecases.*
import dev.fajar.starter.workforce.domain.entities.*
import dev.fajar.starter.workforce.domain.policies.admitWorkforce
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.*
import kotlinx.datetime.*

class AttendanceFixture {
    val base = WorkCalendarFixture()
    val now = base.clock.now()
    val access =
        (admitWorkforce(base.workspace.id, base.workspace, base.session, base.build, now)
                as AppResult.Success)
            .value
    val period =
        CalendarPeriod(
            base.directory.asOf.minus(5, DateTimeUnit.DAY),
            base.directory.asOf.plus(1, DateTimeUnit.DAY),
        )
    val initial =
        AttendanceSnapshot(
            access,
            "generation",
            base.directory,
            base.employee.id,
            period,
            WorkCalendar(
                null,
                List(7) { WorkDay.Unassigned(period.from.plus(it, DateTimeUnit.DAY)) },
            ),
            List(7) {
                AttendanceDay(
                    period.from.plus(it, DateTimeUnit.DAY),
                    emptyList(),
                    0,
                    0,
                    false,
                    null,
                )
            },
            now,
            now + 1.hours,
        )
    val snapshot = MutableStateFlow<AppResult<AttendanceSnapshot?>>(AppResult.Success(initial))
    val records = MutableStateFlow<List<AttendanceCommandRecord>>(emptyList())
    var locationReads = 0
    var permissionRequests = 0
    var windowReads = 0
    val sent = mutableListOf<AttendanceCommand>()
    var permission = LocationPermission.Granted
    var fixCall: suspend () -> AppResult<LocationFix> = {
        AppResult.Success(LocationFix(1.0, 2.0, 3.0, now, false))
    }
    var windowCall: suspend () -> AppResult<AttendanceCaptureWindow> = {
        AppResult.Success(
            AttendanceCaptureWindow(
                "44444444-4444-4444-4444-444444444444",
                base.employee.id,
                access.sessionId,
                now,
                now + 2.minutes,
            )
        )
    }
    var sendCall: suspend (AttendanceCommand) -> AppResult<AttendanceReceipt> = {
        AppResult.Success(AttendanceReceipt(it.id, 0))
    }
    var findCall: suspend () -> Unit = {}
    val cache =
        object : AttendanceCacheRepository {
            override fun observe(access: WorkforceAccess) = snapshot

            override suspend fun generation(access: WorkforceAccess) =
                AppResult.Success((snapshot.value as? AppResult.Success)?.value?.generation)

            override suspend fun replace(
                access: WorkforceAccess,
                expectedGeneration: String?,
                snapshot: AttendanceSnapshot?,
            ): AppResult<Boolean> {
                if (
                    (this@AttendanceFixture.snapshot.value as? AppResult.Success)
                        ?.value
                        ?.generation != expectedGeneration
                )
                    return AppResult.Success(false)
                this@AttendanceFixture.snapshot.value = AppResult.Success(snapshot)
                return AppResult.Success(true)
            }
        }
    val commands =
        object : AttendanceCommandRepository {
            override fun observe(access: WorkforceAccess, resourceId: String?) =
                records.map { rows ->
                    AppResult.Success(
                        rows.filter { resourceId == null || it.command.resourceId == resourceId }
                    )
                }

            override suspend fun find(
                access: WorkforceAccess,
                id: String,
            ): AppResult<AttendanceCommandRecord?> {
                findCall()
                return AppResult.Success(records.value.singleOrNull { it.command.id == id })
            }

            override suspend fun page(access: WorkforceAccess, cursor: String?) =
                AppResult.Success(AttendanceCommandPage(records.value, null))

            override suspend fun insert(
                access: WorkforceAccess,
                command: AttendanceCommand,
            ): AppResult<AttendanceCommandAdmission> {
                if (records.value.any { it.command.resourceId == command.resourceId })
                    return AppResult.Success(AttendanceCommandAdmission.OCCUPIED)
                records.value += AttendanceCommandRecord(command)
                return AppResult.Success(AttendanceCommandAdmission.STORED)
            }

            override suspend fun update(
                access: WorkforceAccess,
                expected: AttendanceCommandRecord,
                outcome: AttendanceCommandOutcome,
                attempts: Int,
            ): AppResult<Boolean> {
                if (expected !in records.value) return AppResult.Success(false)
                records.value =
                    records.value.map {
                        if (it == expected)
                            it.copy(
                                revision = it.revision + 1,
                                attempts = attempts,
                                outcome = outcome,
                            )
                        else it
                    }
                return AppResult.Success(true)
            }

            override suspend fun remove(
                access: WorkforceAccess,
                expected: AttendanceCommandRecord,
            ): AppResult<Boolean> {
                if (expected !in records.value) return AppResult.Success(false)
                records.value -= expected
                return AppResult.Success(true)
            }
        }
    val location =
        object : LocationRepository {
            override suspend fun access() = AppResult.Success(LocationAccess(permission, true))

            override suspend fun requestPermission(): AppResult<LocationAccess> {
                permissionRequests++
                return access()
            }

            override suspend fun current(): AppResult<LocationFix> {
                locationReads++
                return fixCall()
            }

            override suspend fun openSettings(target: LocationSettings) = AppResult.Success(Unit)
        }
    val remote =
        object : AttendanceRepository {
            override suspend fun days(
                access: WorkforceAccess,
                employeeId: String,
                period: CalendarPeriod,
            ) = AppResult.Success(initial.days)

            override suspend fun window(
                access: WorkforceAccess,
                employeeId: String,
                deviceId: String,
                operationId: String,
            ): AppResult<AttendanceCaptureWindow> {
                windowReads++
                return windowCall()
            }

            override suspend fun record(
                access: WorkforceAccess,
                command: AttendanceCommand,
            ): AppResult<AttendanceReceipt> {
                sent += command
                return sendCall(command)
            }
        }
    val capture =
        CaptureAttendance(
            remote,
            cache,
            commands,
            location,
            base.sessions,
            base.workspaces,
            base.clock,
            base.build,
        )
    val replay =
        ReplayAttendance(
            commands,
            remote,
            cache,
            base.sessions,
            base.workspaces,
            base.clock,
            base.build,
        )
    val dismiss =
        DismissAttendanceRejection(commands, base.sessions, base.workspaces, base.clock, base.build)
}
