package dev.fajar.starter.workforce.domain.overtime

import dev.fajar.starter.common.result.*
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.domain.overtime.usecases.ObserveOvertimePending
import dev.fajar.starter.workforce.domain.overtime.working.entities.*
import dev.fajar.starter.workspace.domain.entities.PendingActionSummary
import kotlin.test.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate

class ObserveOvertimePendingTest {
    @Test
    fun planAndActionDraftsRemainSeparateAndFrozenPlanIsNotCountedTwice() = runTest {
        val f = OvertimeCommandFixture()
        assertIs<AppResult.Success<*>>(f.queue())
        val record = f.records.value.single()
        val plan = assertIs<OvertimeCommandPayload.Plan>(record.command.payload)
        val copy =
            OvertimePlanWorkingCopy(
                record.command.employeeId,
                OvertimePlanWorkingInput(LocalDate(2026, 10, 15)),
                "plan-revision",
                f.base.clock.now(),
            )
        f.working.stored = copy
        val action =
            OvertimeActionWorkingCopy(
                record.command.requestId,
                copy.employeeId,
                OvertimeAction.WITHDRAW,
                OvertimeActionWorkingInput(0, null, "Changed plans"),
                "action-revision",
                f.base.clock.now(),
            )
        f.actionWorking.records[action.requestId to action.action] = action
        val observe =
            ObserveOvertimePending(
                f.working.copies,
                f.actionWorking.copies,
                f.commands,
                f.overtime.replicas,
                f.base.sessions,
                f.base.workspaces,
                f.base.clock,
                f.base.build,
            )
        suspend fun summary() =
            assertIs<AppResult.Success<PendingActionSummary>>(
                    observe.observePending(f.workspace.id).first()
                )
                .value
        assertEquals(PendingActionSummary(drafts = 2, queued = 1), summary())
        f.records.value =
            listOf(
                record.copy(
                    command =
                        record.command.copy(payload = plan.copy(workingRevision = copy.revision))
                )
            )
        assertEquals(PendingActionSummary(drafts = 1, queued = 1), summary())
        f.actionWorking.records[action.requestId to action.action] = action.copy(hasEdits = false)
        assertEquals(PendingActionSummary(queued = 1), summary())
        val failure = Failure(FailureKind.Network, "Unknown outcome")
        f.records.value =
            f.records.value.map { it.copy(outcome = OvertimeCommandOutcome.Unconfirmed(failure)) }
        assertEquals(PendingActionSummary(unconfirmed = 1), summary())
        f.records.value =
            f.records.value.map { it.copy(outcome = OvertimeCommandOutcome.Rejected(failure)) }
        assertEquals(PendingActionSummary(needsReview = 1), summary())
        assertTrue(f.transmitted.isEmpty())
    }
}
