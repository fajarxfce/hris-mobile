@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.workforce.domain.replica

import dev.fajar.starter.common.result.*
import dev.fajar.starter.workforce.domain.attendance.AttendanceFixture
import dev.fajar.starter.workforce.domain.attendance.entities.*
import dev.fajar.starter.workforce.domain.entities.*
import dev.fajar.starter.workforce.domain.replica.entities.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.datetime.*

class WorkforceObservationTest {
    @Test
    fun monthAndEmploymentSelectionsReadLocallyWithoutReplacingCaptureOrOtherMonths() = runTest {
        val attendance = AttendanceFixture()
        val f = WorkforceSyncFixture(attendance.base)
        f.sync(f.base.workspace.id)
        val before = attendance.snapshot.value
        val monthly = AttendanceSelection(window = AttendanceWindow.MONTHLY)
        for (date in
            listOf(LocalDate(2026, 10, 1), LocalDate(2020, 2, 1), LocalDate(2026, 10, 1))) {
            val saved =
                assertIs<AppResult.Success<AttendanceView>>(
                        attendance.observe(f.base.workspace.id, monthly.copy(from = date)).first()
                    )
                    .value
                    .snapshot!!
            assertEquals(date, saved.period.from)
            assertEquals(
                if (date.year == 2020) 0 else 1,
                saved.days.count { it.entries.isNotEmpty() || it.correction != null },
            )
            assertEquals(if (date.year == 2020) 29 else 31, saved.calendar!!.days.size)
            assertEquals(f.base.clock.now(), saved.fetchedAt)
        }
        assertEquals(4, f.canonicalReads.size)
        assertEquals(before, attendance.snapshot.value)
        assertEquals(0, attendance.locationReads)
        assertTrue(attendance.sent.isEmpty())
    }

    @Test
    fun oldFactsRemainReadableAfterReauthenticationWithoutRefreshingTheirTimestamp() = runTest {
        val attendance = AttendanceFixture()
        val f = WorkforceSyncFixture(attendance.base)
        f.sync(f.base.workspace.id)
        val old = (f.replicas.published.value as AppResult.Success).value
        val checkpoint =
            old.checkpoint!!.copy(
                refreshedAt = f.base.clock.now() - 90.days,
                refreshAfter = f.base.clock.now() - 89.days,
            )
        f.replicas.published.value = AppResult.Success(old.copy(checkpoint = checkpoint))
        val session = f.base.session.copy(id = "another-login")
        f.base.sessionState.value = AppResult.Success(session)
        f.base.workspaceState.value =
            AppResult.Success(f.base.workspace.copy(sessionId = session.id))
        val snapshot =
            (attendance.observe(f.base.workspace.id).first() as AppResult.Success).value.snapshot!!
        assertEquals(session.id, snapshot.access.sessionId)
        assertEquals(checkpoint.refreshedAt, snapshot.fetchedAt)
        assertEquals(checkpoint.refreshAfter, snapshot.refreshAfter)
        assertEquals(4, f.canonicalReads.size)
    }

    @Test
    fun permissionLossAndDisposalReleaseEveryLocalCollector() = runTest {
        val f = WorkforceSyncFixture()
        f.sync(f.base.workspace.id)
        val values = mutableListOf<AppResult<WorkCalendarSnapshot?>>()
        val owner =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.base.observe(f.base.workspace.id).toList(values)
            }
        assertEquals(1, f.replicas.directoryObservers)
        assertEquals(1, f.replicas.factsObservers)
        f.base.workspaceState.value =
            AppResult.Success(f.base.workspace.copy(permissions = emptySet()))
        runCurrent()
        assertEquals("access_denied", assertIs<AppResult.Failed>(values.last()).failure.code)
        assertEquals(0, f.replicas.directoryObservers)
        assertEquals(0, f.replicas.factsObservers)
        owner.cancelAndJoin()
        val count = values.size
        f.base.workspaceState.value = AppResult.Success(f.base.workspace)
        runCurrent()
        assertEquals(count, values.size)
    }

    @Test
    fun cancelledLocalQueriesCannotPublishThePreviousSelection() = runTest {
        val attendance = AttendanceFixture()
        val f = WorkforceSyncFixture(attendance.base)
        f.sync(f.base.workspace.id)
        val selection = MutableStateFlow(AttendanceSelection(window = AttendanceWindow.MONTHLY))
        val values = mutableListOf<AppResult<AttendanceView>>()
        val release = CompletableDeferred<Unit>()
        f.replicas.factsGate = release
        val reader =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                selection
                    .flatMapLatest { attendance.observe(f.base.workspace.id, it) }
                    .toList(values)
            }
        selection.value = selection.value.copy(from = LocalDate(2026, 9, 1))
        runCurrent()
        release.complete(Unit)
        runCurrent()
        assertEquals(
            LocalDate(2026, 9, 1),
            (values.single() as AppResult.Success).value.snapshot!!.period.from,
        )
        assertEquals(4, f.canonicalReads.size)
        reader.cancelAndJoin()
        assertEquals(0, f.replicas.factsObservers)
    }

    @Test
    fun invalidSelectionAndAnUnownedEmployeeNeverOpenFactQueries() = runTest {
        val f = WorkforceSyncFixture()
        f.sync(f.base.workspace.id)
        for (invalid in
            listOf(
                WorkCalendarSelection(employeeId = "../foreign"),
                WorkCalendarSelection(after = " "),
                WorkCalendarSelection(from = LocalDate(2026, 10, 13)),
                WorkCalendarSelection(employeeId = workforceId(999)),
            )) {
            assertIs<AppResult.Failed>(f.base.observe(f.base.workspace.id, invalid).first())
        }
        assertTrue(f.replicas.selections.isEmpty())
    }
}
