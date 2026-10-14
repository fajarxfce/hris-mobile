@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.workforce.domain.replica

import dev.fajar.starter.common.result.*
import dev.fajar.starter.sync.domain.SyncResult
import dev.fajar.starter.sync.domain.entities.*
import dev.fajar.starter.workforce.domain.attendance.AttendanceFixture
import dev.fajar.starter.workforce.domain.attendance.entities.*
import dev.fajar.starter.workforce.domain.entities.WorkDay
import dev.fajar.starter.workforce.domain.policies.calendarFailure
import dev.fajar.starter.workforce.domain.replica.entities.WorkforceRecord
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class AttendanceReplicaCommandsTest {
    @Test
    fun captureUsesTheReplicaAndConfirmationRetiresOnlyAfterTheExactServerEntryArrives() = runTest {
        val attendance = AttendanceFixture()
        val f = WorkforceSyncFixture(attendance.base)
        attendance.snapshot.value = AppResult.Success(null)
        f.sync(f.base.workspace.id)
        val captured =
            assertIs<AppResult.Success<AttendanceCommandRecord>>(
                    attendance.capture(
                        f.base.workspace.id,
                        f.base.employee.id,
                        AttendanceKind.CLOCK_IN,
                        true,
                    )
                )
                .value
        assertEquals(f.base.directory.asOf, captured.command.workDate)
        assertTrue(captured.command.offline)
        assertIs<AppResult.Success<*>>(attendance.replay(f.base.workspace.id, captured.command.id))
        assertEquals(SyncResult.Complete, attendance.replay())
        assertEquals(1, attendance.records.value.size)
        val day =
            AttendanceDay(
                captured.command.workDate,
                listOf(
                    AttendanceEntry(
                        captured.command.id,
                        AttendanceKind.CLOCK_IN,
                        attendance.now,
                        attendance.now,
                        true,
                        AttendanceStatus.ACCEPTED,
                        emptySet(),
                        WorkDay.Unassigned(captured.command.workDate),
                        0,
                    )
                ),
                0,
                0,
                true,
                null,
            )
        val record = WorkforceRecord.Attendance(workforceId(444), 0, f.base.employee.id, day)
        f.records[SyncCollection.ATTENDANCE_DAYS to record.id] = record
        f.changesCall = {
            f.changes(
                listOf(SyncChange(workforceReference(record), SyncOperation.UPSERT)),
                "captured",
            )
        }
        f.sync(f.base.workspace.id)
        // Read age does not prevent reconciliation of an already confirmed immutable command.
        val old = (f.replicas.published.value as AppResult.Success).value
        f.replicas.published.value =
            AppResult.Success(
                old.copy(
                    checkpoint =
                        old.checkpoint!!.copy(
                            refreshedAt = attendance.now - 90.days,
                            refreshAfter = attendance.now - 89.days,
                        )
                )
            )
        assertEquals(SyncResult.Complete, attendance.replay())
        assertTrue(attendance.records.value.isEmpty())
        assertEquals(1, attendance.sent.size)
    }

    @Test
    fun cancelledLocalCaptureDoesNotAcquireLocationOrRetainACommand() = runTest {
        val attendance = AttendanceFixture()
        val f = WorkforceSyncFixture(attendance.base)
        f.sync(f.base.workspace.id)
        f.replicas.factsGate = CompletableDeferred()
        val capture = launch {
            attendance.capture(f.base.workspace.id, f.base.employee.id, AttendanceKind.CLOCK_IN)
        }
        runCurrent()
        capture.cancelAndJoin()
        assertEquals(0, attendance.locationReads)
        assertTrue(attendance.records.value.isEmpty())
        assertEquals(0, f.replicas.factsObservers)
    }

    @Test
    fun aRacingLocalCommitIsReadAgainBeforeCaptureAndWaitingForStorageIsFinite() = runTest {
        val attendance = AttendanceFixture()
        val f = WorkforceSyncFixture(attendance.base)
        f.sync(f.base.workspace.id)
        val release = CompletableDeferred<Unit>()
        f.replicas.factsGate = release
        val pending = async {
            attendance.capture(
                f.base.workspace.id,
                f.base.employee.id,
                AttendanceKind.CLOCK_IN,
                true,
            )
        }
        runCurrent()
        f.sync(f.base.workspace.id)
        release.complete(Unit)
        assertIs<AppResult.Success<*>>(pending.await())
        assertEquals(2, f.replicas.selections.size)
        f.replicas.factsGate = CompletableDeferred()
        val result =
            attendance.capture(
                f.base.workspace.id,
                f.base.employee.id,
                AttendanceKind.CLOCK_OUT,
                true,
            )
        assertEquals("client.attendance_timeout", assertIs<AppResult.Failed>(result).failure.code)
        assertEquals(5_000, testScheduler.currentTime)
    }

    @Test
    fun failedReplicaCleanupNeverChangesACommandDeliveryDenialIntoAStorageFailure() = runTest {
        val attendance = AttendanceFixture()
        val f = WorkforceSyncFixture(attendance.base)
        f.sync(f.base.workspace.id)
        val command =
            assertIs<AppResult.Success<AttendanceCommandRecord>>(
                    attendance.capture(
                        f.base.workspace.id,
                        f.base.employee.id,
                        AttendanceKind.CLOCK_IN,
                        true,
                    )
                )
                .value
        val denial = calendarFailure("company_access_denied", FailureKind.AccessDenied)
        f.base.policyCall = { denial }
        f.replicas.failCleanup = true
        assertSame(denial, attendance.replay(f.base.workspace.id, command.command.id))
        assertEquals(listOf(command), attendance.records.value)
        assertTrue(attendance.sent.isEmpty())
    }
}
