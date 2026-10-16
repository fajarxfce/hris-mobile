@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.workforce.domain.overtime

import dev.fajar.starter.common.result.*
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.domain.overtime.working.entities.*
import kotlin.test.*
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.*

class OvertimeWorkingSubmissionTest {
    @Test
    fun preparationRequiresTheAcknowledgedInputAndRetainsItsRevision() = runTest {
        val f = OvertimeCommandFixture()
        val copy = planInput(f)
        f.working.stored = copy
        val changed =
            f.preparePlan(f.workspace.id, f.prepared, f.interval, "Different reason", copy.revision)
        assertEquals(
            "client.overtime_working_copy_changed",
            assertIs<AppResult.Failed>(changed).failure.code,
        )
        assertEquals(0, f.inserts)
        val missing =
            f.preparePlan(
                f.workspace.id,
                f.prepared,
                f.interval,
                copy.input.reason,
                Uuid.random().toString(),
            )
        assertIs<AppResult.Failed>(missing)
        assertEquals(0, f.inserts)
        val record =
            assertIs<AppResult.Success<OvertimeCommandRecord>>(
                    f.preparePlan(
                        f.workspace.id,
                        f.prepared,
                        f.interval,
                        copy.input.reason,
                        copy.revision,
                    )
                )
                .value
        assertEquals(
            copy.revision,
            (record.command.payload as OvertimeCommandPayload.Plan).workingRevision,
        )
        assertEquals(copy.input.reason, record.command.reason)
        assertTrue(f.transmitted.isEmpty())
    }

    @Test
    fun confirmedDeliveryKeepsTheDraftUntilAdmittedRequestFactsAreAvailable() = runTest {
        val f = OvertimeCommandFixture()
        val copy = planInput(f)
        f.working.stored = copy
        assertIs<AppResult.Success<OvertimeCommandRecord>>(
            f.preparePlan(f.workspace.id, f.prepared, f.interval, copy.input.reason, copy.revision)
        )
        assertIs<AppResult.Success<OvertimeCommandRecord>>(f.send())
        assertEquals(copy, f.working.stored)
        assertIs<AppResult.Failed>(f.clear())
        assertEquals(0, f.working.removals)
        f.publish()
        assertEquals(AppResult.Success(true), f.clear())
        assertNull(f.working.stored)
        assertTrue(f.records.value.isEmpty())
    }

    @Test
    fun cleanupFailureKeepsTheConfirmedCommandRecoverableWithoutResubmitting() = runTest {
        val f = OvertimeCommandFixture()
        val copy = planInput(f)
        f.working.stored = copy
        assertIs<AppResult.Success<OvertimeCommandRecord>>(
            f.preparePlan(f.workspace.id, f.prepared, f.interval, copy.input.reason, copy.revision)
        )
        f.send()
        f.publish()
        val saved = f.records.value.single()
        f.working.removeFailure = Failure(FailureKind.Storage, "Unavailable")
        assertIs<AppResult.Failed>(f.clear())
        assertEquals(saved, f.records.value.single())
        assertEquals(copy, f.working.stored)
        f.working.removeFailure = null
        assertEquals(AppResult.Success(true), f.clear())
        assertEquals(1, f.transmitted.size)
        assertNull(f.working.stored)
    }

    @Test
    fun confirmedCommandCannotDeleteAnInputEditedAfterItsOriginalSubmission() = runTest {
        val f = OvertimeCommandFixture()
        val copy = planInput(f)
        f.working.stored = copy
        assertIs<AppResult.Success<OvertimeCommandRecord>>(
            f.preparePlan(f.workspace.id, f.prepared, f.interval, copy.input.reason, copy.revision)
        )
        f.send()
        f.publish()
        val next =
            copy.copy(
                revision = Uuid.random().toString(),
                input = copy.input.copy(reason = "Another plan"),
            )
        f.working.stored = next
        assertEquals(AppResult.Success(true), f.clear())
        assertEquals(next, f.working.stored)
        assertTrue(f.records.value.isEmpty())
    }

    @Test
    fun legacyAndDiscardedUnsentCommandsDoNotDeleteWorkingInput() = runTest {
        val f = OvertimeCommandFixture()
        val copy = planInput(f)
        f.working.stored = copy
        assertIs<AppResult.Success<OvertimeCommandRecord>>(f.queue())
        f.send()
        f.publish()
        assertEquals(AppResult.Success(true), f.clear())
        assertEquals(copy, f.working.stored)
        assertIs<AppResult.Success<OvertimeCommandRecord>>(
            f.preparePlan(f.workspace.id, f.prepared, f.interval, copy.input.reason, copy.revision)
        )
        assertEquals(AppResult.Success(true), f.clear())
        assertEquals(copy, f.working.stored)
        assertEquals(0, f.working.removals)
    }

    @Test
    fun cancellationDuringCleanupCannotRetireTheConfirmedCommand() = runTest {
        val f = OvertimeCommandFixture()
        val copy = planInput(f)
        f.working.stored = copy
        assertIs<AppResult.Success<OvertimeCommandRecord>>(
            f.preparePlan(f.workspace.id, f.prepared, f.interval, copy.input.reason, copy.revision)
        )
        f.send()
        f.publish()
        val entered = CompletableDeferred<Unit>()
        f.working.beforeRemove = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val clearing = async { f.clear() }
        entered.await()
        clearing.cancelAndJoin()
        assertIs<OvertimeCommandOutcome.Confirmed>(f.records.value.single().outcome)
        assertEquals(copy, f.working.stored)
    }
}

private fun planInput(f: OvertimeCommandFixture): OvertimePlanWorkingCopy {
    val zone = TimeZone.of(f.prepared.review.timezone)
    val start = f.interval.startsAt.toLocalDateTime(zone)
    val end = f.interval.endsAt.toLocalDateTime(zone)
    return OvertimePlanWorkingCopy(
        f.prepared.selection.employeeId,
        OvertimePlanWorkingInput(
            f.prepared.selection.workDate,
            start.date,
            end.date,
            start.time,
            end.time,
            f.interval.breakMinutes.toString(),
            "Inventory count",
        ),
        Uuid.random().toString(),
        f.base.clock.now(),
    )
}
