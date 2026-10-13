@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.expenses.domain

import dev.fajar.starter.common.decimal.DecimalAmount
import dev.fajar.starter.common.result.*
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.policies.*
import dev.fajar.starter.expenses.domain.usecases.PrepareExpenseLine
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class ExpenseDraftCommandTest {
    @Test
    fun linePreparationPreservesExistingIdsExactAmountsAndEvidence() {
        val f = ExpenseFixture()
        val useCase = PrepareExpenseLine()
        val input =
            ExpenseLineInput(
                f.line.id,
                f.line.categoryId,
                "2026-10-13",
                " 150000.20 ",
                " Taxi ",
                receiptRevisionIds = listOf(f.claim.id),
            )
        val existing = assertIs<AppResult.Success<ExpenseLine>>(useCase(input)).value
        assertEquals(f.line.id, existing.id)
        assertEquals("150000.2", existing.amount.value)
        assertEquals("Taxi", existing.description)
        assertEquals(listOf(f.claim.id), existing.receiptIds)
        val fresh = assertIs<AppResult.Success<ExpenseLine>>(useCase(input.copy(id = null))).value
        assertIs<AppResult.Success<Unit>>(validateExpenseId(fresh.id))
        assertNotEquals(existing.id, fresh.id)
    }

    @Test
    fun invalidLineInputsAndTotalLimitsCannotBePrepared() {
        val f = ExpenseFixture()
        val useCase = PrepareExpenseLine()
        val input = ExpenseLineInput(f.line.id, f.line.categoryId, "2026-10-13", "1.01", "Taxi")
        for (invalid in
            listOf(
                input.copy(amount = "1,000"),
                input.copy(amount = "1.001"),
                input.copy(amount = "NaN"),
                input.copy(amount = "0"),
                input.copy(amount = "-1"),
                input.copy(amount = "1000000000000"),
                input.copy(occurredOn = "2026-02-30"),
                input.copy(occurredOn = "2201-01-01"),
                input.copy(description = ""),
                input.copy(categoryId = "invalid"),
                input.copy(receiptRevisionIds = listOf(f.claim.id, f.claim.id)),
                input.copy(receiptRevisionIds = List(4) { f.claim.id }),
            )) assertIs<AppResult.Failed>(useCase(invalid))
        val maximum = f.line.copy(amount = DecimalAmount.parse("999999999999.99"))
        val draft = ExpenseDraftInput("Trip", "", listOf(maximum), "Saved")
        assertIs<AppResult.Success<*>>(normalizeExpenseDraft(draft, null))
        val tooMuch = draft.copy(lines = listOf(maximum, f.line.copy(id = f.claim.id)))
        assertEquals(
            "expense_claim_amount_limit",
            assertIs<AppResult.Failed>(normalizeExpenseDraft(tooMuch, null)).failure.code,
        )
        assertIs<AppResult.Success<*>>(normalizeExpenseDraft(draft.copy(lines = emptyList()), null))
    }

    @Test
    fun creationAndEditingPersistImmutableIntentBeforeAnyTransport() = runTest {
        for (editing in listOf(false, true)) {
            val p = ExpenseCommandFixture()
            val first = assertIs<AppResult.Success<ExpenseCommandRecord>>(p.save(editing)).value
            val again = assertIs<AppResult.Success<ExpenseCommandRecord>>(p.save(editing)).value
            assertEquals(first, again)
            val payload = assertIs<ExpenseCommandPayload.SaveDraft>(first.command.payload)
            assertEquals(if (editing) 0L else null, payload.expectedVersion)
            assertEquals(p.f.line, payload.lines.single())
            assertEquals(1, p.insertions)
            assertTrue(p.sent.isEmpty())
            assertEquals(0, p.observers)
            assertIs<AppResult.Failed>(
                p.prepare(
                    p.f.workspace.id,
                    if (editing) p.preparation.copy(claim = p.f.claim) else p.preparation,
                    p.input.copy(title = "Different"),
                )
            )
            assertEquals(first, p.values.value.single())
        }
    }

    @Test
    fun refreshedDraftCannotSilentlyReplaceTheVersionTheEmployeeEdited() = runTest {
        val p = ExpenseCommandFixture()
        val refreshed = p.preparation.copy(claim = p.f.claim.copy(version = 1))
        for (expectedVersion in listOf(null, 0L, 2L)) {
            val failure =
                assertIs<AppResult.Failed>(
                        p.prepare(p.f.workspace.id, refreshed, p.input, expectedVersion)
                    )
                    .failure
            assertEquals("client.expense_changed", failure.code)
            assertEquals(FailureKind.Conflict, failure.kind)
        }
        assertEquals(0, p.insertions)
        assertTrue(p.values.value.isEmpty())
        assertTrue(p.sent.isEmpty())
        val saved =
            assertIs<AppResult.Success<ExpenseCommandRecord>>(
                    p.prepare(p.f.workspace.id, refreshed, p.input, expectedVersion = 1)
                )
                .value
        assertEquals(
            1L,
            assertIs<ExpenseCommandPayload.SaveDraft>(saved.command.payload).expectedVersion,
        )
    }

    @Test
    fun simultaneousSavesShareOneSlotAndCannotReplaceAnotherBody() = runTest {
        val p = ExpenseCommandFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var arrivals = 0
        p.beforeInsert = {
            arrivals++
            if (arrivals == 1) {
                entered.complete(Unit)
                release.await()
            }
        }
        val first = async { p.save() }
        entered.await()
        val second = assertIs<AppResult.Success<ExpenseCommandRecord>>(p.save()).value
        release.complete(Unit)
        assertEquals(second, assertIs<AppResult.Success<ExpenseCommandRecord>>(first.await()).value)
        assertEquals(1, p.values.value.size)
    }

    @Test
    fun expiryScopeChangesAndCancellationCannotStartStorageAfterPendingRead() = runTest {
        for (case in 0..2) {
            var now = Instant.parse("2026-10-13T00:00:00Z")
            val p = ExpenseCommandFixture { now }
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            p.beforeRead = {
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
            }
            val pending = async { p.save() }
            entered.await()
            when (case) {
                0 ->
                    p.f.workspaceState.value =
                        AppResult.Success(p.f.workspace.copy(id = "replaced"))
                1 -> now += 61.seconds
                else -> pending.cancel()
            }
            release.complete(Unit)
            if (case == 2) assertFailsWith<CancellationException> { pending.await() }
            else assertIs<AppResult.Failed>(pending.await())
            assertEquals(0, p.insertions)
            assertTrue(p.sent.isEmpty())
            assertEquals(0, p.observers)
        }
    }

    @Test
    fun submitKeepsItsOriginalGeneratedIdAndReturnedClaimsRequireAnEditedDraft() = runTest {
        val p = ExpenseCommandFixture()
        val snapshot = p.details()
        val first =
            assertIs<AppResult.Success<ExpenseCommandRecord>>(
                    p.action(p.f.workspace.id, snapshot, ExpenseRequestedAction.SUBMIT, "Submit")
                )
                .value
        val second =
            assertIs<AppResult.Success<ExpenseCommandRecord>>(
                    p.action(p.f.workspace.id, snapshot, ExpenseRequestedAction.SUBMIT, "Submit")
                )
                .value
        assertEquals(first, second)
        assertIs<ExpenseCommandPayload.Submit>(first.command.payload)
        assertEquals(1, p.insertions)
        assertIs<AppResult.Failed>(
            p.action(p.f.workspace.id, snapshot, ExpenseRequestedAction.CANCEL, "Cancel")
        )
        val returned =
            snapshot.copy(
                value =
                    snapshot.value.copy(
                        claim = snapshot.value.claim.copy(status = ExpenseStatus.RETURNED)
                    )
            )
        assertEquals(
            "expense_not_editable",
            assertIs<AppResult.Failed>(
                    p.action(p.f.workspace.id, returned, ExpenseRequestedAction.SUBMIT, "Submit")
                )
                .failure
                .code,
        )
    }

    @Test
    fun withdrawalRequiresTheCurrentPendingOrBlockedApproval() = runTest {
        val p = ExpenseCommandFixture()
        val f = p.f
        val snapshot = p.details()
        val submissionId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        val approval =
            ExpenseApproval(
                "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                ExpenseApprovalStatus.BLOCKED,
                0,
                0,
                listOf(setOf(f.session.user.id)),
            )
        val submission =
            ExpenseSubmission(
                submissionId,
                f.claim.id,
                f.employee.id,
                1,
                0,
                f.draft.title,
                f.draft.description,
                f.amount,
                "IDR",
                emptyList(),
                f.clock.now(),
                "Submitted",
                approval,
                1,
                ExpenseStatus.PENDING,
                true,
                emptyList(),
            )
        val current =
            snapshot.copy(
                value =
                    snapshot.value.copy(
                        claim =
                            f.claim.copy(
                                version = 1,
                                status = ExpenseStatus.PENDING,
                                latestSubmissionId = submissionId,
                            ),
                        submission = submission,
                    )
            )
        val pending =
            assertIs<AppResult.Success<ExpenseCommandRecord>>(
                    p.action(f.workspace.id, current, ExpenseRequestedAction.WITHDRAW, "Withdraw")
                )
                .value
        assertEquals(ExpenseCommandPayload.Withdraw(1, submissionId), pending.command.payload)
        val approved =
            current.copy(
                value =
                    current.value.copy(
                        submission =
                            submission.copy(
                                approval = approval.copy(status = ExpenseApprovalStatus.APPROVED)
                            )
                    )
            )
        assertIs<AppResult.Failed>(
            p.action(f.workspace.id, approved, ExpenseRequestedAction.WITHDRAW, "Withdraw")
        )
    }
}
