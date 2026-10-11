@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.workforce.domain.attendance

import dev.fajar.starter.common.result.*
import dev.fajar.starter.workforce.domain.attendance.entities.*
import dev.fajar.starter.workforce.domain.attendance.policies.*
import dev.fajar.starter.workforce.domain.entities.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
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

    @Test
    fun monthlyReadsAndRefreshFailuresNeverReplaceTheCaptureSnapshot() = runTest {
        val f = AttendanceFixture()
        val capture = f.snapshot.value
        assertIs<AppResult.Success<Unit>>(f.refresh(f.base.workspace.id, monthly))
        val month = assertIs<AppResult.Success<AttendanceSnapshot?>>(f.history.value).value!!
        assertEquals(31, month.days.size)
        assertEquals(LocalDate(2026, 10, 1), month.period.from)
        assertEquals(capture, f.snapshot.value)
        assertEquals(0, f.locationReads)
        assertEquals(0, f.windowReads)
        assertTrue(f.sent.isEmpty())
        val failure = AppResult.Failed(Failure(FailureKind.Network, "fixture", code = "offline"))
        f.daysCall = { failure }
        assertSame(failure, f.refresh(f.base.workspace.id, monthly))
        assertEquals(month, assertIs<AppResult.Success<AttendanceSnapshot?>>(f.history.value).value)
        f.daysCall = {
            AppResult.Failed(Failure(FailureKind.AccessDenied, "fixture", code = "access_denied"))
        }
        assertIs<AppResult.Failed>(f.refresh(f.base.workspace.id, monthly))
        assertEquals(AppResult.Success(null), f.history.value)
        assertEquals(capture, f.snapshot.value)
    }

    @Test
    fun cancelledMonthAcquisitionCannotOverwriteTheReplacementMonth() = runTest {
        val f = AttendanceFixture()
        val release = CompletableDeferred<Unit>()
        val regular = f.daysCall
        f.daysCall = { period ->
            if (period.from.month == Month.OCTOBER) withContext(NonCancellable) { release.await() }
            regular(period)
        }
        val first = async { f.refresh(f.base.workspace.id, monthly) }
        runCurrent()
        first.cancel()
        val replacement = async {
            f.refresh(f.base.workspace.id, monthly.copy(from = LocalDate(2026, 9, 1)))
        }
        runCurrent()
        release.complete(Unit)
        first.join()
        assertIs<AppResult.Success<Unit>>(replacement.await())
        assertEquals(
            LocalDate(2026, 9, 1),
            assertIs<AppResult.Success<AttendanceSnapshot?>>(f.history.value).value!!.period.from,
        )
        assertEquals(
            f.initial,
            assertIs<AppResult.Success<AttendanceSnapshot?>>(f.snapshot.value).value,
        )
    }

    @Test
    fun replacingCompanyWhileAReadFailsCannotClearAnotherScopesSnapshot() = runTest {
        val f = AttendanceFixture()
        f.refresh(f.base.workspace.id, monthly)
        val saved = f.history.value
        val release = CompletableDeferred<Unit>()
        f.daysCall = {
            release.await()
            AppResult.Failed(
                Failure(FailureKind.AccessDenied, "fixture", code = "company_access_denied")
            )
        }
        val pending = async { f.refresh(f.base.workspace.id, monthly) }
        runCurrent()
        f.base.workspaceState.value = AppResult.Success(f.base.workspace.copy(id = "replacement"))
        release.complete(Unit)
        assertEquals(
            "client.workspace_changed",
            assertIs<AppResult.Failed>(pending.await()).failure.code,
        )
        assertEquals(saved, f.history.value)
        assertIs<AppResult.Failed>(f.observe(f.base.workspace.id, AttendanceWindow.MONTHLY).first())
    }

    @Test
    fun anEmptyDirectoryStillPublishesTheMonthWithoutReadingAnEmployee() = runTest {
        val f = AttendanceFixture()
        f.base.directoryCall = { AppResult.Success(f.base.directory.copy(items = emptyList())) }
        assertIs<AppResult.Success<Unit>>(f.refresh(f.base.workspace.id, monthly))
        val saved = assertIs<AppResult.Success<AttendanceSnapshot?>>(f.history.value).value!!
        assertNull(saved.employeeId)
        assertTrue(saved.days.isEmpty())
        assertTrue(f.requestedPeriods.isEmpty())
        assertEquals(0, f.base.calendarReads)
    }
}
