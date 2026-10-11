package dev.fajar.starter.workforce.data

import dev.fajar.starter.workforce.data.datasources.WorkforceRemoteDataSource
import dev.fajar.starter.workforce.data.dto.*
import dev.fajar.starter.workforce.domain.entities.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.datetime.*

class WorkforceDataFixture : WorkforceRemoteDataSource {
    val now = Instant.parse("2026-10-12T01:00:00Z")
    val access =
        CalendarAccess(
            "session",
            "11111111-1111-1111-1111-111111111111",
            "https://hris.example.test/",
            "22222222-2222-2222-2222-222222222222",
            setOf("attendance.self.record"),
            now + 24.hours,
            now + 1.hours,
            "client.workspace_expired",
        )
    val employee =
        WorkforceEmploymentDto("33333333-3333-3333-3333-333333333333", "E01", "ACTIVE", 0)
    val period = CalendarPeriod(LocalDate(2026, 10, 12), LocalDate(2026, 10, 18))
    var directory =
        WorkforceDirectoryDto(
            access.accountId,
            access.companyId,
            period.from.toString(),
            "Asia/Jakarta",
            WorkforcePageDto(listOf(employee)),
        )
    val shift =
        CalendarShiftDto(
            "44444444-4444-4444-4444-444444444444",
            0,
            CalendarShiftDetailsDto(
                "NIGHT",
                "Night shift",
                "22:00:00",
                "06:00:00",
                60,
                "Asia/Jakarta",
                "ONSITE",
            ),
        )
    val workDay =
        WorkDayDto(
            period.from.toString(),
            "WORK",
            shift,
            "2026-10-12T15:00:00Z",
            "2026-10-12T23:00:00Z",
            420,
            "ROSTER",
            2,
        )
    var calendar =
        WorkCalendarDto(
            null,
            List(7) { index ->
                when (index) {
                    0 -> workDay
                    1 ->
                        WorkDayDto(
                            "2026-10-13",
                            "OFF",
                            origin = "HOLIDAY",
                            originVersion = 0,
                            holidayId = "55555555-5555-5555-5555-555555555555",
                        )
                    else ->
                        WorkDayDto(
                            period.from.plus(index, DateTimeUnit.DAY).toString(),
                            "UNASSIGNED",
                        )
                }
            },
        )
    var failure: Exception? = null

    override suspend fun selfEmployments(
        sessionId: String,
        companyId: String,
        after: String?,
    ): WorkforceDirectoryDto {
        failure?.let { throw it }
        return directory
    }

    override suspend fun calendar(
        sessionId: String,
        companyId: String,
        employeeId: String,
        from: String,
        until: String,
    ): WorkCalendarDto {
        failure?.let { throw it }
        return calendar
    }
}
