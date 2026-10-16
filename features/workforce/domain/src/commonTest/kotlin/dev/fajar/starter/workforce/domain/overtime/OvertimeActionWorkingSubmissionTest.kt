@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.workforce.domain.overtime

import dev.fajar.starter.common.result.*
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.domain.overtime.working.entities.OvertimeActionWorkingCopy
import kotlin.test.*
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class OvertimeActionWorkingSubmissionTest {
    @Test
    fun bothCommandsPinTheExactAcknowledgedInput() = runTest {
        for (action in listOf(OvertimeAction.SUBMIT_ACTUAL, OvertimeAction.WITHDRAW)) {
            val f = OvertimeCommandFixture()
            val copy = draft(f, action)
            val requested = requested(f, action)
            for ((revision, reason) in
                listOf(
                    Uuid.random().toString() to copy.input.reason,
                    copy.revision to "Different reason",
                )) {
                assertEquals(
                    "client.overtime_working_copy_changed",
                    assertIs<AppResult.Failed>(
                            f.prepareAction(f.workspace.id, f.snapshot, requested, reason, revision)
                        )
                        .failure
                        .code,
                )
            }
            assertEquals(0, f.inserts)
            val record =
                assertIs<AppResult.Success<OvertimeCommandRecord>>(
                        f.prepareAction(
                            f.workspace.id,
                            f.snapshot,
                            requested,
                            copy.input.reason,
                            copy.revision,
                        )
                    )
                    .value
            assertEquals(copy.revision, record.command.payload.workingRevision)
            assertEquals(1, f.inserts)
        }
    }

    @Test
    fun changedActualTimeCannotBePreparedAgainstTheSavedRevision() = runTest {
        val f = OvertimeCommandFixture()
        val copy = draft(f, OvertimeAction.SUBMIT_ACTUAL)
        val changed = OvertimeRequestedAction.Actual(f.interval.copy(breakMinutes = 15))
        assertEquals(
            "client.overtime_working_copy_changed",
            assertIs<AppResult.Failed>(
                    f.prepareAction(
                        f.workspace.id,
                        f.snapshot,
                        changed,
                        copy.input.reason,
                        copy.revision,
                    )
                )
                .failure
                .code,
        )
        assertEquals(0, f.inserts)
    }

    @Test
    fun cleanupWaitsForCanonicalConfirmationAndPreservesTheOtherAction() = runTest {
        for (action in listOf(OvertimeAction.SUBMIT_ACTUAL, OvertimeAction.WITHDRAW)) {
            val f = OvertimeCommandFixture()
            val copy = draft(f, action)
            val other =
                draft(
                    f,
                    if (action == OvertimeAction.WITHDRAW) OvertimeAction.SUBMIT_ACTUAL
                    else OvertimeAction.WITHDRAW,
                )
            assertIs<AppResult.Success<OvertimeCommandRecord>>(
                f.prepareAction(
                    f.workspace.id,
                    f.snapshot,
                    requested(f, action),
                    copy.input.reason,
                    copy.revision,
                )
            )
            assertIs<AppResult.Success<OvertimeCommandRecord>>(f.send())
            assertEquals(
                "client.overtime_updates_pending",
                assertIs<AppResult.Failed>(f.clear()).failure.code,
            )
            assertEquals(0, f.actionWorking.removals)
            f.publish()
            assertEquals(AppResult.Success(true), f.clear())
            assertEquals(other, f.actionWorking.records.values.single())
            assertTrue(f.records.value.isEmpty())
        }
    }

    @Test
    fun newerEditSurvivesAnOlderCommandsConfirmation() = runTest {
        val f = OvertimeCommandFixture()
        val copy = draft(f, OvertimeAction.WITHDRAW)
        assertIs<AppResult.Success<OvertimeCommandRecord>>(
            f.prepareAction(
                f.workspace.id,
                f.snapshot,
                OvertimeRequestedAction.Withdraw,
                copy.input.reason,
                copy.revision,
            )
        )
        val newer =
            copy.copy(
                revision = Uuid.random().toString(),
                input = copy.input.copy(reason = "Newer input"),
            )
        assertIs<AppResult.Success<OvertimeActionWorkingCopy>>(
            f.actionWorking.save(f.workspace.id, f.overtime.selection, copy, newer)
        )
        f.send()
        f.publish()
        assertEquals(AppResult.Success(true), f.clear())
        assertEquals(newer.input, f.actionWorking.records.values.single().input)
    }

    @Test
    fun failedOrCancelledDraftRemovalKeepsTheConfirmedCommandRecoverable() = runTest {
        val f = OvertimeCommandFixture()
        val copy = draft(f, OvertimeAction.WITHDRAW)
        f.prepareAction(
            f.workspace.id,
            f.snapshot,
            OvertimeRequestedAction.Withdraw,
            copy.input.reason,
            copy.revision,
        )
        f.send()
        f.publish()
        val confirmed = f.records.value.single()
        f.actionWorking.removeFailure = Failure(FailureKind.Storage, "Unavailable")
        assertIs<AppResult.Failed>(f.clear())
        assertEquals(confirmed, f.records.value.single())
        f.actionWorking.removeFailure = null
        val entered = CompletableDeferred<Unit>()
        f.actionWorking.beforeRemove = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val pending = async { f.clear() }
        entered.await()
        pending.cancelAndJoin()
        assertEquals(confirmed, f.records.value.single())
        assertEquals(copy, f.actionWorking.records.values.single())
        f.actionWorking.beforeRemove = {}
        assertEquals(AppResult.Success(true), f.clear())
        assertTrue(f.actionWorking.records.isEmpty())
    }

    @Test
    fun unsentAndLegacyCommandsDoNotDeleteWorkingInput() = runTest {
        for (confirmedLegacy in listOf(false, true)) {
            val f = OvertimeCommandFixture()
            val copy = draft(f, OvertimeAction.WITHDRAW)
            f.prepareAction(
                f.workspace.id,
                f.snapshot,
                OvertimeRequestedAction.Withdraw,
                copy.input.reason,
                if (confirmedLegacy) null else copy.revision,
            )
            if (confirmedLegacy) {
                f.send()
                f.publish()
            }
            assertEquals(AppResult.Success(true), f.clear())
            assertEquals(copy, f.actionWorking.records.values.single())
            assertEquals(0, f.actionWorking.removals)
        }
    }

    private suspend fun draft(
        f: OvertimeCommandFixture,
        action: OvertimeAction,
    ): OvertimeActionWorkingCopy {
        f.actionWorking.seedOwnership()
        val copy =
            assertIs<AppResult.Success<OvertimeActionWorkingCopy>>(
                    f.actionWorking.open(f.workspace.id, f.overtime.selection, action)
                )
                .value
        return assertIs<AppResult.Success<OvertimeActionWorkingCopy>>(
                f.actionWorking.save(
                    f.workspace.id,
                    f.overtime.selection,
                    copy,
                    copy.copy(
                        revision = Uuid.random().toString(),
                        input = copy.input.copy(reason = "Schedule changed"),
                    ),
                )
            )
            .value
    }

    private fun requested(f: OvertimeCommandFixture, action: OvertimeAction) =
        if (action == OvertimeAction.SUBMIT_ACTUAL) OvertimeRequestedAction.Actual(f.interval)
        else OvertimeRequestedAction.Withdraw
}
