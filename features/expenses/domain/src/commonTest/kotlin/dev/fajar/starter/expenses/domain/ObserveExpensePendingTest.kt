package dev.fajar.starter.expenses.domain

import dev.fajar.starter.common.result.*
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.usecases.ObserveExpensePending
import dev.fajar.starter.expenses.domain.working.entities.*
import dev.fajar.starter.workspace.domain.entities.PendingActionSummary
import kotlin.test.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class ObserveExpensePendingTest {
    @Test
    fun frozenSaveIsCountedOnceButAnUnfinishedItemAndNewEditsRemainDrafts() = runTest {
        val fixture = ExpenseCommandFixture()
        val f = fixture.f
        val record = assertIs<AppResult.Success<ExpenseCommandRecord>>(fixture.save()).value
        val copy =
            ExpenseWorkingCopy(
                record.command.claimId,
                f.employee,
                f.access.timezone,
                fixture.input,
                null,
                null,
                "draft-revision",
                f.clock.now(),
            )
        val slot = ExpenseWorkingSlot.Creation(f.employee.id)
        fixture.copies.seed(f.access, slot, copy)
        val observe =
            ObserveExpensePending(
                fixture.copies,
                fixture.commands,
                f.replicas,
                f.sessions,
                f.workspaces,
                f.clock,
                f.build,
            )
        suspend fun summary() =
            assertIs<AppResult.Success<PendingActionSummary>>(
                    observe.observePending(f.workspace.id).first()
                )
                .value
        assertEquals(PendingActionSummary(queued = 1), summary())
        val failure = Failure(FailureKind.Network, "Unknown outcome")
        fixture.values.value =
            listOf(record.copy(outcome = ExpenseCommandOutcome.Unconfirmed(failure)))
        assertEquals(PendingActionSummary(unconfirmed = 1), summary())
        fixture.copies.seed(
            f.access,
            slot,
            copy.copy(
                item = ExpenseWorkingItem(ExpenseLineInput("new-item", "", "2026-10-13", "12.", ""))
            ),
        )
        assertEquals(PendingActionSummary(drafts = 1, unconfirmed = 1), summary())
        fixture.copies.seed(
            f.access,
            slot,
            copy.copy(input = copy.input.copy(title = "Newer edit")),
        )
        assertEquals(PendingActionSummary(drafts = 1, unconfirmed = 1), summary())
        fixture.copies.seed(f.access, slot, copy.copy(hasEdits = false))
        fixture.values.value =
            listOf(record.copy(outcome = ExpenseCommandOutcome.Rejected(failure)))
        assertEquals(PendingActionSummary(needsReview = 1), summary())
        assertTrue(fixture.sent.isEmpty())
    }
}
