@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.expenses.presentation

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.common.result.*
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.policies.expenseFailure
import dev.fajar.starter.expenses.presentation.item.*
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*

class ExpenseItemViewModelTest {
    @Test
    fun resolvingLabelsDoesNotMarkAnUneditedItemDirtyAndBackEmitsOnlyOnce() = runTest {
        withModel(existing = true) { _, model, _ ->
            val effects = mutableListOf<ExpenseItemEffect>()
            backgroundScope.launch { model.effects.collect { effects += it } }
            model.onEvent(ExpenseItemEvent.Entered)
            runCurrent()
            assertEquals("Travel", model.state.value.form.category?.name)
            assertFalse(model.state.value.dirty)
            model.onEvent(ExpenseItemEvent.BackRequested)
            model.onEvent(ExpenseItemEvent.BackRequested)
            runCurrent()
            assertEquals(listOf<ExpenseItemEffect>(ExpenseItemEffect.Back), effects)
        }
    }

    @Test
    fun commaDecimalsAreValidatedAsExactAmountsAndRepeatedSaveReturnsOneItem() = runTest {
        withModel { f, model, _ ->
            val effects = mutableListOf<ExpenseItemEffect>()
            backgroundScope.launch { model.effects.collect { effects += it } }
            model.onEvent(ExpenseItemEvent.Entered)
            runCurrent()
            model.onEvent(ExpenseItemEvent.SaveRequested)
            runCurrent()
            assertTrue(effects.isEmpty())
            assertEquals("invalid_expense_line", model.state.value.failure?.code)
            model.onEvent(ExpenseItemEvent.CategoryOpened)
            runCurrent()
            model.onEvent(ExpenseItemEvent.CategorySelected(f.category.id))
            runCurrent()
            model.onEvent(ExpenseItemEvent.AmountChanged("75000,25"))
            model.onEvent(ExpenseItemEvent.DescriptionChanged("Client transport"))
            runCurrent()
            model.onEvent(ExpenseItemEvent.SaveRequested)
            model.onEvent(ExpenseItemEvent.SaveRequested)
            runCurrent()
            val saved = assertIs<ExpenseItemEffect.Saved>(effects.single()).value
            assertEquals("75000.25", saved.line.amount.value)
            assertEquals(f.category.id, saved.line.categoryId)
            assertTrue(model.state.value.closing)
            assertEquals(0, f.insertions + f.sent.size)
        }
    }

    @Test
    fun costCenterSearchDebouncesAndRejectsLateFailureFromAnOldQuery() = runTest {
        withModel { f, model, _ ->
            model.onEvent(ExpenseItemEvent.Entered)
            runCurrent()
            model.onEvent(ExpenseItemEvent.CostCenterOpened)
            runCurrent()
            var cancelled = false
            f.centerCall = { query, _ ->
                if (query == "old") {
                    try {
                        awaitCancellation()
                    } catch (_: CancellationException) {
                        cancelled = true
                    }
                    expenseFailure("mfa_required", FailureKind.AccessDenied)
                } else AppResult.Success(ExpensePage(listOf(f.center.copy(name = query))))
            }
            model.onEvent(ExpenseItemEvent.CostCenterQueryChanged("old"))
            runCurrent()
            assertEquals(1, f.centerReads)
            advanceTimeBy(300)
            runCurrent()
            assertEquals(2, f.centerReads)
            model.onEvent(ExpenseItemEvent.CostCenterQueryChanged("new"))
            runCurrent()
            assertTrue(cancelled)
            advanceTimeBy(300)
            runCurrent()
            assertEquals("new", model.state.value.costCenters?.page?.items?.single()?.name)
            assertNull(model.state.value.restriction)
            model.onEvent(ExpenseItemEvent.CostCenterSelected("foreign"))
            runCurrent()
            assertNull(model.state.value.form.costCenter)
            model.onEvent(ExpenseItemEvent.CostCenterSelected(f.center.id))
            runCurrent()
            assertEquals(f.center.id, model.state.value.form.costCenter?.id)
        }
    }

    @Test
    fun unavailableRowsForeignIdsUnissuedCursorsAndExpiredChoicesCannotBeSelected() = runTest {
        withModel { f, model, _ ->
            val inactive = f.category.copy(id = f.center.id, active = false)
            f.categoryCall = { _, _ ->
                AppResult.Success(ExpensePage(listOf(inactive, f.category)))
            }
            model.onEvent(ExpenseItemEvent.Entered)
            runCurrent()
            model.onEvent(ExpenseItemEvent.CategoryOpened)
            runCurrent()
            model.onEvent(ExpenseItemEvent.CategorySelected("foreign"))
            model.onEvent(ExpenseItemEvent.CategorySelected(inactive.id))
            model.onEvent(ExpenseItemEvent.CategoryPageRequested("UNISSUED"))
            runCurrent()
            assertNull(model.state.value.form.category)
            assertEquals(1, f.categoryReads)
            advanceTimeBy(61_000)
            runCurrent()
            assertNull(model.state.value.categories)
            model.onEvent(ExpenseItemEvent.CategorySelected(f.category.id))
            runCurrent()
            assertNull(model.state.value.form.category)
            model.onEvent(ExpenseItemEvent.ReloadRequested)
            runCurrent()
            model.onEvent(ExpenseItemEvent.CategorySelected(f.category.id))
            runCurrent()
            assertEquals(f.category.id, model.state.value.form.category?.id)
        }
    }

    @Test
    fun backgroundCancelsPendingChoicesAndKeepsLocalFieldsForReadmission() = runTest {
        withModel { f, model, owner ->
            model.onEvent(ExpenseItemEvent.Entered)
            runCurrent()
            model.onEvent(ExpenseItemEvent.AmountChanged("90000"))
            model.onEvent(ExpenseItemEvent.DescriptionChanged("Taxi"))
            runCurrent()
            var cancelled = false
            f.categoryCall = { _, _ ->
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
            model.onEvent(ExpenseItemEvent.CategoryOpened)
            runCurrent()
            model.onEvent(ExpenseItemEvent.Stopped)
            runCurrent()
            assertTrue(cancelled)
            assertFalse(model.state.value.editable)
            assertEquals("90000", model.state.value.form.amount)
            assertEquals("Taxi", model.state.value.form.description)
            f.categoryCall = { _, _ -> AppResult.Success(ExpensePage(listOf(f.category))) }
            model.onEvent(ExpenseItemEvent.Entered)
            runCurrent()
            assertTrue(model.state.value.editable)
            assertNotNull(model.state.value.categories)
            owner.clear()
            runCurrent()
            assertEquals(0, f.observers)
        }
    }

    @Test
    fun onlyTheCurrentReceiptPickerCanReturnUniqueBoundedAttachments() = runTest {
        withModel { _, model, _ ->
            model.onEvent(ExpenseItemEvent.Entered)
            runCurrent()
            model.onEvent(ExpenseItemEvent.ReceiptOpened)
            runCurrent()
            val old = assertNotNull(model.state.value.evidenceTarget)
            model.onEvent(ExpenseItemEvent.ReceiptDismissed(old))
            runCurrent()
            model.onEvent(ExpenseItemEvent.ReceiptOpened)
            runCurrent()
            val current = assertNotNull(model.state.value.evidenceTarget)
            model.onEvent(ExpenseItemEvent.ReceiptSelected(old, expenseReceipt()))
            runCurrent()
            assertTrue(model.state.value.form.receipts.isEmpty())
            model.onEvent(ExpenseItemEvent.ReceiptSelected(current, expenseReceipt()))
            runCurrent()
            assertEquals(1, model.state.value.form.receipts.size)
            for (index in 2..3) {
                model.onEvent(ExpenseItemEvent.ReceiptOpened)
                runCurrent()
                val selected = assertNotNull(model.state.value.evidenceTarget)
                model.onEvent(ExpenseItemEvent.ReceiptSelected(selected, expenseReceipt(index)))
                runCurrent()
            }
            model.onEvent(ExpenseItemEvent.ReceiptOpened)
            runCurrent()
            assertNull(model.state.value.evidenceTarget)
            assertEquals(3, model.state.value.form.receipts.size)
            model.onEvent(ExpenseItemEvent.ReceiptRemoved(expenseReceipt().revisionId))
            runCurrent()
            assertEquals(2, model.state.value.form.receipts.size)
        }
    }

    @Test
    fun changingDateClearsItsCategoryWithoutLosingTheOtherLocalFields() = runTest {
        withModel(existing = true) { _, model, _ ->
            model.onEvent(ExpenseItemEvent.Entered)
            runCurrent()
            model.onEvent(ExpenseItemEvent.DateOpened)
            runCurrent()
            model.onEvent(ExpenseItemEvent.DateSelected(32))
            runCurrent()
            assertNotNull(model.state.value.dialog)
            model.onEvent(ExpenseItemEvent.DateSelected(14))
            runCurrent()
            assertEquals(14, model.state.value.form.date.day)
            assertNull(model.state.value.form.category)
            assertEquals("120000.25", model.state.value.form.amount)
            assertEquals("Client transport", model.state.value.form.description)
        }
    }

    private suspend fun TestScope.withModel(
        existing: Boolean = false,
        block:
            suspend TestScope.(ExpenseEditorFixture, ExpenseItemViewModel, ViewModelStore) -> Unit,
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = ExpenseEditorFixture {
            Instant.parse("2026-10-13T00:00:00Z") + testScheduler.currentTime.milliseconds
        }
        val model = f.itemModel(f.itemTarget(existing))
        val owner = ViewModelStore().apply { put("item", model) }
        try {
            block(f, model, owner)
        } finally {
            owner.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }
}
