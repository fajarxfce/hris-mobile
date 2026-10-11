package dev.fajar.starter.workforce.domain.attendance.policies

import dev.fajar.starter.common.result.*
import dev.fajar.starter.location.domain.entities.LocationFix
import dev.fajar.starter.workforce.domain.attendance.entities.*
import dev.fajar.starter.workforce.domain.entities.*
import dev.fajar.starter.workforce.domain.policies.sameWorkforceScope
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.datetime.*

val ATTENDANCE_CACHE_PERIOD = 1.hours
const val ATTENDANCE_AUTOMATIC_ATTEMPTS = 5
const val ATTENDANCE_MAXIMUM_ATTEMPTS = 20

fun attendanceFailure(code: String, kind: FailureKind = FailureKind.Validation) =
    AppResult.Failed(Failure(kind, "Attendance could not be completed.", code = code))

fun validateAttendanceCache(
    access: WorkforceAccess,
    snapshot: AttendanceSnapshot?,
    now: Instant,
): AppResult<Unit> =
    when {
        access.readUntil <= now -> attendanceFailure(access.expiryCode, FailureKind.AccessDenied)
        snapshot == null -> AppResult.Success(Unit)
        !sameWorkforceScope(access, snapshot.access) ->
            attendanceFailure("client.attendance_scope_changed", FailureKind.AccessDenied)
        snapshot.fetchedAt > now ||
            snapshot.expiresAt <= now ||
            snapshot.expiresAt > snapshot.fetchedAt + ATTENDANCE_CACHE_PERIOD ->
            attendanceFailure("client.attendance_cache_expired", FailureKind.AccessDenied)
        else -> AppResult.Success(Unit)
    }

fun attendanceWorkDate(snapshot: AttendanceSnapshot, now: Instant): LocalDate {
    val today = now.toLocalDateTime(TimeZone.of(snapshot.directory.timezone)).date
    val previous = today.minus(1, DateTimeUnit.DAY)
    val overnight =
        snapshot.calendar?.days?.filterIsInstance<WorkDay.Work>()?.singleOrNull {
            it.date == previous && it.endsAt >= now && it.startsAt <= now
        }
    return overnight?.date ?: today
}

fun validateAttendanceCapture(
    access: WorkforceAccess,
    snapshot: AttendanceSnapshot?,
    employeeId: String,
    now: Instant,
): AppResult<LocalDate> {
    val admitted = validateAttendanceCache(access, snapshot, now)
    if (admitted is AppResult.Failed) return admitted
    if ("attendance.self.record" !in access.permissions)
        return attendanceFailure("access_denied", FailureKind.AccessDenied)
    if (
        snapshot == null ||
            snapshot.employeeId != employeeId ||
            snapshot.directory.items.none {
                it.id == employeeId &&
                    it.status in
                        setOf(WorkforceEmploymentStatus.ACTIVE, WorkforceEmploymentStatus.PROBATION)
            }
    )
        return attendanceFailure("client.attendance_refresh_required")
    val date = attendanceWorkDate(snapshot, now)
    if (date < snapshot.period.from || date > snapshot.period.until)
        return attendanceFailure("client.attendance_refresh_required")
    if (snapshot.days.any { it.date == date && it.correction != null })
        return attendanceFailure("attendance_day_corrected")
    return AppResult.Success(date)
}

fun validateAttendanceLocation(fix: LocationFix, now: Instant): AppResult<Unit> =
    if (
        fix.observedAt > now + 1.minutes ||
            now - fix.observedAt > 1.minutes ||
            fix.accuracyMeters > 100_000
    )
        attendanceFailure("client.location_stale")
    else AppResult.Success(Unit)

fun resolveAttendanceCommandFailure(
    previous: AttendanceCommandOutcome,
    failure: Failure,
): AttendanceCommandOutcome =
    if (previous is AttendanceCommandOutcome.Unconfirmed)
        AttendanceCommandOutcome.Unconfirmed(failure)
    else if (
        failure.kind in
            setOf(
                FailureKind.Validation,
                FailureKind.Conflict,
                FailureKind.Permission,
                FailureKind.AccessDenied,
                FailureKind.Unauthorized,
            ) && failure.code !in setOf("client.invalid_response", "rate_limited")
    )
        AttendanceCommandOutcome.Rejected(failure)
    else AttendanceCommandOutcome.Unconfirmed(failure)
