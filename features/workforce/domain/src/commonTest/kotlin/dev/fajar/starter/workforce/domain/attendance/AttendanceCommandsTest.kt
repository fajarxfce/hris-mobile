package dev.fajar.starter.workforce.domain.attendance

import dev.fajar.starter.common.result.*
import dev.fajar.starter.location.domain.entities.LocationPermission
import dev.fajar.starter.workforce.domain.attendance.entities.*
import dev.fajar.starter.workforce.domain.attendance.policies.*
import dev.fajar.starter.workforce.domain.attendance.usecases.ObserveAttendance
import dev.fajar.starter.workforce.domain.entities.*
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.datetime.*

@OptIn(ExperimentalCoroutinesApi::class)
class AttendanceCommandsTest {
    @Test
    fun aDeniedPermissionCanRecoverAfterSettingsWithoutRepositoryPolicy() = runTest {
        val f = AttendanceFixture()
        f.permission = LocationPermission.PermanentlyDenied
        val denied = f.capture(f.base.workspace.id, f.base.employee.id, AttendanceKind.CLOCK_IN)
        assertEquals(
            "client.location_permanently_denied",
            assertIs<AppResult.Failed>(denied).failure.code,
        )
        assertEquals(0, f.permissionRequests)
        assertEquals(0, f.locationReads)
        assertTrue(f.records.value.isEmpty())
        f.permission = LocationPermission.Granted
        assertIs<AppResult.Success<*>>(
            f.capture(f.base.workspace.id, f.base.employee.id, AttendanceKind.CLOCK_IN)
        )
        assertEquals(1, f.locationReads)
        assertTrue(f.sent.isEmpty())
    }

    @Test
    fun cancellingAnAcquisitionDiscardsItsLateFixAndDoesNotSaveOrSend() = runTest {
        val f = AttendanceFixture()
        val entered = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        val original = f.fixCall
        f.fixCall = {
            withContext(NonCancellable) {
                entered.complete(Unit)
                released.await()
                original()
            }
        }
        val capture = async {
            f.capture(f.base.workspace.id, f.base.employee.id, AttendanceKind.CLOCK_IN)
        }
        entered.await()
        capture.cancel()
        released.complete(Unit)
        assertFailsWith<CancellationException> { capture.await() }
        assertTrue(f.records.value.isEmpty())
        assertEquals(0, f.windowReads)
    }

    @Test
    fun companyReplacementDuringAcquisitionCannotPersistTheOldCapture() = runTest {
        val f = AttendanceFixture()
        val original = f.fixCall
        f.fixCall = {
            f.base.workspaceState.value = AppResult.Success(f.base.workspace.copy(id = "another"))
            original()
        }
        assertIs<AppResult.Failed>(
            f.capture(f.base.workspace.id, f.base.employee.id, AttendanceKind.CLOCK_IN)
        )
        assertTrue(f.records.value.isEmpty())
    }

    @Test
    fun concurrentCaptureUsesOneImmutableRecordAndAWindowFailureCreatesOfflineEvidence() = runTest {
        val f = AttendanceFixture()
        f.windowCall = { AppResult.Failed(Failure(FailureKind.Network, "offline")) }
        val captures =
            List(2) {
                    async {
                        f.capture(f.base.workspace.id, f.base.employee.id, AttendanceKind.CLOCK_IN)
                    }
                }
                .awaitAll()
        val first = assertIs<AppResult.Success<AttendanceCommandRecord>>(captures[0]).value
        assertEquals(first, assertIs<AppResult.Success<AttendanceCommandRecord>>(captures[1]).value)
        assertEquals(1, f.locationReads)
        assertTrue(first.command.offline)
        assertNull(first.command.windowId)
        assertEquals(1, f.records.value.size)
    }

    @Test
    fun lostResponseAndLaterMfaRejectionRetainTheOriginalEvidenceForExplicitRetry() = runTest {
        val f = AttendanceFixture()
        val prepared =
            assertIs<AppResult.Success<AttendanceCommandRecord>>(
                    f.capture(f.base.workspace.id, f.base.employee.id, AttendanceKind.CLOCK_IN)
                )
                .value
        f.sendCall = { AppResult.Failed(Failure(FailureKind.Network, "lost")) }
        assertIs<AppResult.Success<*>>(f.replay(f.base.workspace.id, prepared.command.id))
        assertIs<AttendanceCommandOutcome.Unconfirmed>(f.records.value.single().outcome)
        f.sendCall = {
            AppResult.Failed(Failure(FailureKind.AccessDenied, "expired", code = "mfa_required"))
        }
        f.replay(f.base.workspace.id, prepared.command.id)
        assertIs<AttendanceCommandOutcome.Unconfirmed>(f.records.value.single().outcome)
        assertIs<AppResult.Failed>(
            f.dismiss(f.base.workspace.id, prepared.command.id, f.records.value.single().revision)
        )
        f.sendCall = { AppResult.Success(AttendanceReceipt(it.id, 0)) }
        f.replay(f.base.workspace.id, prepared.command.id)
        assertIs<AttendanceCommandOutcome.Confirmed>(f.records.value.single().outcome)
        assertEquals(listOf(prepared.command, prepared.command, prepared.command), f.sent)
    }

    @Test
    fun cancellationDuringHttpLeavesAnUnconfirmedDurableCommand() = runTest {
        val f = AttendanceFixture()
        val prepared =
            assertIs<AppResult.Success<AttendanceCommandRecord>>(
                    f.capture(f.base.workspace.id, f.base.employee.id, AttendanceKind.CLOCK_IN)
                )
                .value
        val entered = CompletableDeferred<Unit>()
        f.sendCall = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val pending = launch { f.replay(f.base.workspace.id, prepared.command.id) }
        entered.await()
        pending.cancelAndJoin()
        assertIs<AttendanceCommandOutcome.Unconfirmed>(f.records.value.single().outcome)
        assertEquals(prepared.command, f.records.value.single().command)
    }

    @Test
    fun companyChangeWhileReadingARejectionPreventsItsRemoval() = runTest {
        val f = AttendanceFixture()
        val prepared =
            assertIs<AppResult.Success<AttendanceCommandRecord>>(
                    f.capture(f.base.workspace.id, f.base.employee.id, AttendanceKind.CLOCK_IN)
                )
                .value
        f.records.value =
            listOf(
                prepared.copy(
                    outcome =
                        AttendanceCommandOutcome.Rejected(Failure(FailureKind.Validation, "bad")),
                    attempts = 1,
                )
            )
        f.findCall = {
            f.base.workspaceState.value = AppResult.Success(f.base.workspace.copy(id = "another"))
        }
        assertIs<AppResult.Failed>(
            f.dismiss(f.base.workspace.id, prepared.command.id, prepared.revision)
        )
        assertEquals(1, f.records.value.size)
    }

    @Test
    fun savedAttendanceRemainsReadableForDaysAndReleasesAllCollectionsWhenDisposed() = runTest {
        val f = AttendanceFixture()
        val values = mutableListOf<AppResult<AttendanceView>>()
        val owner =
            launch(UnconfinedTestDispatcher(testScheduler)) {
                ObserveAttendance(
                        f.cache,
                        f.commands,
                        f.base.sessions,
                        f.base.workspaces,
                        object : Clock {
                            override fun now() = f.now + testScheduler.currentTime.milliseconds
                        },
                        f.base.build,
                    )(f.base.workspace.id)
                    .toList(values)
            }
        val initial = assertIs<AppResult.Success<AttendanceView>>(values.last())
        advanceTimeBy(72.hours)
        runCurrent()
        assertEquals(initial, values.last())
        assertEquals(1, values.size)
        owner.cancelAndJoin()
        assertEquals(0, f.records.subscriptionCount.value)
        assertEquals(0, f.snapshot.subscriptionCount.value)
    }

    @Test
    fun anOvernightShiftRetainsItsCompanyWorkDateAfterMidnight() {
        val f = AttendanceFixture()
        val date = LocalDate(2026, 10, 11)
        val shift =
            CalendarShift(
                "shift",
                0,
                "N",
                "Night",
                LocalTime(22, 0),
                LocalTime(6, 0),
                30,
                "Asia/Jakarta",
                CalendarWorkMode.ONSITE,
            )
        val work =
            WorkDay.Work(
                date,
                shift,
                Instant.parse("2026-10-11T15:00:00Z"),
                Instant.parse("2026-10-11T23:00:00Z"),
                450,
                CalendarOrigin.ROSTER,
                0,
            )
        assertEquals(
            date,
            attendanceWorkDate(
                f.initial.copy(calendar = WorkCalendar(null, listOf(work))),
                Instant.parse("2026-10-11T22:00:00Z"),
            ),
        )
    }
}
