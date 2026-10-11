@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.workforce.data.attendance.mappers

import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.CommandRecord
import dev.fajar.starter.database.CommandScope
import dev.fajar.starter.identity.domain.policies.accountStorageKey
import dev.fajar.starter.location.domain.entities.LocationFix
import dev.fajar.starter.workforce.data.attendance.dto.*
import dev.fajar.starter.workforce.domain.attendance.entities.*
import dev.fajar.starter.workforce.domain.attendance.policies.*
import dev.fajar.starter.workforce.domain.entities.WorkforceAccess
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json

fun attendanceCommandScope(access: WorkforceAccess) =
    CommandScope(access.sessionId, accountStorageKey(access.audience, access.accountId))

fun attendanceCommandPartition(access: WorkforceAccess) = "hris.attendance/" + access.companyId

fun AttendanceCommand.orderKey() =
    capturedAt.toEpochMilliseconds().toString().padStart(15, '0') + "/" + id

fun attendanceCommandAssociatedData(
    access: WorkforceAccess,
    id: String,
    resourceId: String,
    purpose: String,
    revision: Long,
) =
    Json.encodeToString(
            listOf(
                "hris-attendance-command-v1",
                access.audience,
                access.accountId,
                access.companyId,
                id,
                resourceId,
                purpose,
                revision.toString(),
            )
        )
        .encodeToByteArray()

fun AttendanceCommand.toDto() =
    AttendanceCommandDto(
        id,
        accountId,
        audience,
        companyId,
        employeeId,
        toCaptureDto(),
        location?.observedAt?.toString(),
    )

fun AttendanceCommandDto.toCommand(access: WorkforceAccess): AttendanceCommand {
    listOf(id, accountId, companyId, employeeId, capture.deviceId).forEach {
        require(Uuid.parse(it).toString() == it)
    }
    require(capture.id == id && (capture.location == null) == (locationObservedAt == null))
    capture.windowId?.let(Uuid::parse)
    require(!capture.offline || capture.windowId == null)
    val captured = Instant.parse(capture.capturedAt)
    require(captured.toEpochMilliseconds() in 0 until 253_402_300_800_000L)
    val fix =
        capture.location?.let {
            require(
                it.latitude.isFinite() &&
                    it.latitude in -90.0..90.0 &&
                    it.longitude.isFinite() &&
                    it.longitude in -180.0..180.0 &&
                    it.accuracyMeters.isFinite() &&
                    it.accuracyMeters in 0.0..100000.0
            )
            LocationFix(
                it.latitude,
                it.longitude,
                it.accuracyMeters,
                Instant.parse(requireNotNull(locationObservedAt)),
                it.mocked,
            )
        }
    return AttendanceCommand(
            id,
            accountId,
            audience,
            companyId,
            employeeId,
            LocalDate.parse(capture.workDate),
            AttendanceKind.valueOf(capture.kind),
            captured,
            capture.deviceId,
            capture.windowId,
            capture.offline,
            fix,
        )
        .also { require(it.belongsTo(access)) }
}

fun AttendanceCommandOutcome.toDto(attempts: Int): AttendanceCommandOutcomeDto {
    require(attempts in 0..ATTENDANCE_MAXIMUM_ATTEMPTS)
    return when (this) {
        AttendanceCommandOutcome.Pending -> AttendanceCommandOutcomeDto("PENDING", attempts)
        is AttendanceCommandOutcome.Unconfirmed ->
            AttendanceCommandOutcomeDto("UNCONFIRMED", attempts, failure = failure?.toCommandDto())
        is AttendanceCommandOutcome.Rejected ->
            AttendanceCommandOutcomeDto("REJECTED", attempts, failure = failure.toCommandDto())
        is AttendanceCommandOutcome.Confirmed ->
            AttendanceCommandOutcomeDto("CONFIRMED", attempts, version)
    }
}

private fun Failure.toCommandDto() =
    AttendanceCommandFailureDto(kind.name, code, correlationId, retryAfterSeconds)

fun AttendanceCommandOutcomeDto.toRecord(
    command: AttendanceCommand,
    raw: CommandRecord,
    access: WorkforceAccess,
): AttendanceCommandRecord {
    require(
        raw.partition == attendanceCommandPartition(access) &&
            raw.id == command.id &&
            raw.resourceId == command.resourceId &&
            raw.sortKey == command.orderKey()
    )
    require(raw.revision in 0..9_007_199_254_740_991L && attempts in 0..ATTENDANCE_MAXIMUM_ATTEMPTS)
    require(receiptVersion == null || receiptVersion == 0L)
    val reason =
        failure?.let {
            require(it.code == null || it.code.matches(Regex("[A-Za-z0-9_.:-]{1,100}")))
            require(
                it.correlationId == null ||
                    it.correlationId.matches(Regex("[A-Za-z0-9_.:-]{1,128}"))
            )
            require(it.retryAfterSeconds == null || it.retryAfterSeconds in 0..86_400)
            Failure(
                FailureKind.valueOf(it.kind),
                "This attendance action could not be confirmed.",
                code = it.code,
                correlationId = it.correlationId,
                retryAfterSeconds = it.retryAfterSeconds,
            )
        }
    val outcome =
        when (phase) {
            "PENDING" -> {
                require(attempts == 0 && receiptVersion == null && reason == null)
                AttendanceCommandOutcome.Pending
            }
            "UNCONFIRMED" -> {
                require(attempts > 0 && receiptVersion == null)
                AttendanceCommandOutcome.Unconfirmed(reason)
            }
            "REJECTED" -> {
                require(attempts > 0 && receiptVersion == null)
                AttendanceCommandOutcome.Rejected(requireNotNull(reason))
            }
            "CONFIRMED" -> {
                require(attempts > 0 && reason == null)
                AttendanceCommandOutcome.Confirmed(requireNotNull(receiptVersion))
            }
            else -> error("Unsupported attendance command outcome.")
        }
    return AttendanceCommandRecord(command, raw.revision, attempts, outcome)
}
