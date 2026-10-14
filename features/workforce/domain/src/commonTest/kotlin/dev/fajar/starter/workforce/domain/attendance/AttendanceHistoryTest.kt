@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.workforce.domain.attendance

import dev.fajar.starter.common.result.*
import dev.fajar.starter.workforce.domain.attendance.entities.*
import dev.fajar.starter.workforce.domain.attendance.policies.*
import dev.fajar.starter.workforce.domain.entities.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.datetime.*

class AttendanceHistoryTest {
    private val monthly = AttendanceSelection(window = AttendanceWindow.MONTHLY)

    @Test
    fun calendarMonthsAreInclusiveAndFutureOrMalformedSelectionsAreRejected() {
        val today = LocalDate(2026, 10, 12)
        assertEquals(
            AppResult.Success(CalendarPeriod(LocalDate(2026, 10, 1), LocalDate(2026, 10, 31))),
            attendancePeriod(today, monthly),
        )
        assertEquals(
            AppResult.Success(CalendarPeriod(LocalDate(2024, 2, 1), LocalDate(2024, 2, 29))),
            attendancePeriod(today, monthly.copy(from = LocalDate(2024, 2, 1))),
        )
        assertEquals(
            AppResult.Success(CalendarPeriod(LocalDate(2026, 10, 7), LocalDate(2026, 10, 13))),
            attendancePeriod(today, AttendanceSelection()),
        )
        for (invalid in
            listOf(
                monthly.copy(from = LocalDate(2026, 11, 1)),
                monthly.copy(from = LocalDate(2026, 10, 2)),
                monthly.copy(from = LocalDate(1899, 1, 1)),
                monthly.copy(employeeId = "../foreign"),
                monthly.copy(after = " "),
                AttendanceSelection(from = LocalDate(2026, 10, 1)),
            )) assertIs<AppResult.Failed>(attendancePeriod(today, invalid))
    }
}
