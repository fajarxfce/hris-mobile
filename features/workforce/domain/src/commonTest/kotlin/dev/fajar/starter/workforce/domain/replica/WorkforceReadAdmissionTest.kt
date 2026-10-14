@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.workforce.domain.replica

import dev.fajar.starter.common.result.*
import dev.fajar.starter.workforce.domain.attendance.AttendanceFixture
import dev.fajar.starter.workforce.domain.attendance.entities.*
import dev.fajar.starter.workforce.domain.entities.WorkCalendarSnapshot
import dev.fajar.starter.workforce.domain.policies.calendarFailure
import dev.fajar.starter.workforce.domain.replica.entities.WorkforceSyncOutcome
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class WorkforceReadAdmissionTest {
    @Test
    fun aConfirmedDenialClosesEveryProjectionEvenWhenPersistenceAndCleanupFail() = runTest {
        val attendance = AttendanceFixture()
        val f = WorkforceSyncFixture(attendance.base)
        f.sync(f.base.workspace.id)
        val days = mutableListOf<AppResult<AttendanceView>>()
        val calendar = mutableListOf<AppResult<WorkCalendarSnapshot?>>()
        val home =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                attendance.observe(f.base.workspace.id).toList(days)
            }
        val page =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.base.observe(f.base.workspace.id).toList(calendar)
            }
        assertEquals(2, f.replicas.factsObservers)
        val stored = f.replicas.published.value
        val denial = calendarFailure("mfa_required", FailureKind.AccessDenied)
        f.base.policyCall = { denial }
        f.replicas.failCleanup = true
        f.replicas.failAdmissionWrite = true
        assertSame(denial, f.sync(f.base.workspace.id))
        runCurrent()
        assertEquals(stored, f.replicas.published.value)
        assertEquals(0, f.replicas.factsObservers)
        assertEquals(denial, days.last())
        assertEquals(denial, calendar.last())

        f.base.policyCall = { calendarFailure("offline", FailureKind.Network) }
        assertIs<AppResult.Failed>(f.sync(f.base.workspace.id))
        runCurrent()
        assertEquals(denial, days.last())
        assertEquals(denial, calendar.last())
        assertEquals(0, f.replicas.directoryObservers)

        home.cancelAndJoin()
        page.cancelAndJoin()
    }

    @Test
    fun onlyAnAcquisitionStartedAfterTheLatestDenialCanReopenReads() = runTest {
        val f = WorkforceSyncFixture()
        f.sync(f.base.workspace.id)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.changesCall = {
            entered.complete(Unit)
            release.await()
            f.changes()
        }
        val pending = async { f.sync(f.base.workspace.id) }
        entered.await()
        val denial = calendarFailure("mfa_required", FailureKind.AccessDenied)
        f.replicas.restrict(f.access, denial.failure)
        release.complete(Unit)
        assertEquals(denial, pending.await())
        assertEquals(denial, f.base.observe(f.base.workspace.id).first())
        f.changesCall = { f.changes() }
        assertEquals(AppResult.Success(WorkforceSyncOutcome(true, 5)), f.sync(f.base.workspace.id))
        assertIs<AppResult.Success<*>>(f.base.observe(f.base.workspace.id).first())
    }

    @Test
    fun aDenialDuringLocationAcquisitionCannotRetainOrSendAttendance() = runTest {
        val attendance = AttendanceFixture()
        val f = WorkforceSyncFixture(attendance.base)
        f.sync(f.base.workspace.id)
        val release = CompletableDeferred<Unit>()
        val fix = attendance.fixCall
        attendance.fixCall = {
            release.await()
            fix()
        }
        val pending = async {
            attendance.capture(f.base.workspace.id, f.base.employee.id, AttendanceKind.CLOCK_IN)
        }
        runCurrent()
        assertEquals(1, attendance.locationReads)
        val denial = calendarFailure("mfa_required", FailureKind.AccessDenied)
        f.replicas.restrict(f.access, denial.failure)
        release.complete(Unit)
        assertEquals(denial, pending.await())
        assertTrue(attendance.records.value.isEmpty())
        assertTrue(attendance.sent.isEmpty())
        assertEquals(0, attendance.windowReads)
    }
}
