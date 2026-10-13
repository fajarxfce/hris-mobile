@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.expenses.presentation

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.common.result.*
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.policies.expenseFailure
import dev.fajar.starter.expenses.presentation.overview.*
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import kotlinx.datetime.YearMonth

class ExpensesViewModelTest {
    @Test
    fun enteringDropsDuplicatesAndStoppingReleasesAcquisitionAndObservation() = runTest {
        withModel { f, model, owner ->
            model.onEvent(ExpensesEvent.Entered)
            model.onEvent(ExpensesEvent.Entered)
            runCurrent()
            assertEquals(1, f.listReads)
            assertEquals(1, f.observers)
            assertEquals(YearMonth(2026, 10), model.state.value.filters?.month)
            var cancelled = false
            f.listCall = {
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
            model.onEvent(ExpensesEvent.RefreshRequested)
            model.onEvent(ExpensesEvent.RefreshRequested)
            runCurrent()
            assertEquals(2, f.listReads)
            assertNotNull(model.state.value.content)
            model.onEvent(ExpensesEvent.Stopped)
            runCurrent()
            assertTrue(cancelled)
            assertEquals(0, f.observers)
            assertNull(model.state.value.content)
            assertFalse(model.state.value.refreshing)
            f.listCall = { AppResult.Success(ExpensePage(listOf(f.summary))) }
            model.onEvent(ExpensesEvent.Entered)
            runCurrent()
            assertNotNull(model.state.value.content)
            owner.clear()
            runCurrent()
            assertEquals(0, f.observers)
        }
    }

    @Test
    fun changingMonthRejectsCancelledLateFailuresAndKeepsTheNewFilter() = runTest {
        withModel { f, model, _ ->
            model.onEvent(ExpensesEvent.Entered)
            runCurrent()
            var cancelled = false
            f.listCall = { selection ->
                if (selection.month == YearMonth(2026, 10)) {
                    try {
                        awaitCancellation()
                    } catch (_: CancellationException) {
                        cancelled = true
                    }
                    expenseFailure("mfa_required", FailureKind.AccessDenied)
                } else AppResult.Success(ExpensePage(emptyList()))
            }
            model.onEvent(ExpensesEvent.RefreshRequested)
            runCurrent()
            model.onEvent(ExpensesEvent.PreviousMonthRequested)
            runCurrent()
            assertTrue(cancelled)
            assertEquals(YearMonth(2026, 9), model.state.value.filters?.month)
            assertEquals(YearMonth(2026, 9), model.state.value.content?.value?.selection?.month)
            assertNull(model.state.value.failure)
            assertEquals(3, f.listReads)
        }
    }

    @Test
    fun onlyVisibleRowsAndIssuedPageCursorsCanNavigateOrAcquire() = runTest {
        withModel { f, model, _ ->
            val effects = mutableListOf<ExpensesEffect>()
            backgroundScope.launch { model.effects.collect { effects += it } }
            val cursor = f.summary.id
            f.listCall = { selection ->
                AppResult.Success(
                    if (selection.after == null) ExpensePage(listOf(f.summary), cursor)
                    else ExpensePage(emptyList())
                )
            }
            model.onEvent(ExpensesEvent.Entered)
            runCurrent()
            model.onEvent(ExpensesEvent.ClaimSelected("foreign"))
            model.onEvent(ExpensesEvent.PageRequested("foreign"))
            model.onEvent(ExpensesEvent.PageRequested(null))
            model.onEvent(ExpensesEvent.ClaimSelected(f.summary.id))
            runCurrent()
            assertEquals(listOf<ExpensesEffect>(ExpensesEffect.OpenClaim(f.summary.id)), effects)
            assertEquals(1, f.listReads)
            model.onEvent(ExpensesEvent.PageRequested(cursor))
            runCurrent()
            assertEquals(cursor, model.state.value.filters?.after)
            model.onEvent(ExpensesEvent.ClaimSelected(f.summary.id))
            model.onEvent(ExpensesEvent.PageRequested(null))
            runCurrent()
            assertEquals(1, effects.size)
            assertEquals(3, f.listReads)
            assertNull(model.state.value.filters?.after)
        }
    }

    @Test
    fun deniedReadsRemainHiddenAcrossFailedCleanupPendingRetryAndTransportFailure() = runTest {
        withModel { f, model, _ ->
            model.onEvent(ExpensesEvent.Entered)
            runCurrent()
            f.failCleanup = true
            f.listCall = { expenseFailure("mfa_required", FailureKind.AccessDenied) }
            model.onEvent(ExpensesEvent.RefreshRequested)
            runCurrent()
            assertNotNull(f.snapshot(false))
            assertTrue(model.state.value.needsVerification)
            assertNull(model.state.value.content)
            assertEquals(0, f.observers)
            val release = CompletableDeferred<Unit>()
            f.listCall = {
                release.await()
                expenseFailure("fixture.offline", FailureKind.Network)
            }
            model.onEvent(ExpensesEvent.RefreshRequested)
            runCurrent()
            model.onEvent(ExpensesEvent.PreviousMonthRequested)
            assertTrue(model.state.value.readRestricted)
            assertNull(model.state.value.content)
            release.complete(Unit)
            runCurrent()
            assertEquals("mfa_required", model.state.value.failure?.code)
            assertEquals(YearMonth(2026, 10), model.state.value.filters?.month)
            f.listCall = { AppResult.Success(ExpensePage(listOf(f.summary))) }
            model.onEvent(ExpensesEvent.RefreshRequested)
            runCurrent()
            assertNotNull(model.state.value.content)
            assertNull(model.state.value.failure)
            assertEquals(1, f.observers)
        }
    }

    @Test
    fun successfulExplicitAcquisitionReopensACompletedStorageSource() = runTest {
        withModel { f, model, _ ->
            f.failNextObservation = true
            val release = CompletableDeferred<Unit>()
            f.listCall = {
                release.await()
                AppResult.Success(ExpensePage(listOf(f.summary)))
            }
            model.onEvent(ExpensesEvent.Entered)
            runCurrent()
            assertEquals(FailureKind.Storage, model.state.value.failure?.kind)
            assertEquals(0, f.observers)
            release.complete(Unit)
            runCurrent()
            assertNotNull(model.state.value.content)
            assertNull(model.state.value.failure)
            assertEquals(1, f.observers)
            assertEquals(1, f.listReads)
        }
    }

    @Test
    fun expiryAndWorkspaceReplacementHideTheSnapshotWithoutPolling() = runTest {
        withModel { f, model, _ ->
            model.onEvent(ExpensesEvent.Entered)
            runCurrent()
            val saved = assertNotNull(model.state.value.content)
            f.records.value = AppResult.Success(saved.copy(expiresAt = f.clock.now() + 1.seconds))
            runCurrent()
            advanceTimeBy(1_001)
            runCurrent()
            assertEquals("client.expense_cache_expired", model.state.value.failure?.code)
            assertNull(model.state.value.content)
            assertEquals(1, f.listReads)
            model.onEvent(ExpensesEvent.RefreshRequested)
            runCurrent()
            assertNotNull(model.state.value.content)
            f.workspaceState.value = AppResult.Success(f.workspace.copy(id = "replacement"))
            runCurrent()
            assertNull(model.state.value.content)
            assertTrue(model.state.value.readRestricted)
        }
    }

    private suspend fun TestScope.withModel(
        test: suspend (ExpenseFixture, ExpensesViewModel, ViewModelStore) -> Unit
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = ExpenseFixture {
            Instant.parse("2026-10-13T00:00:00Z") + testScheduler.currentTime.milliseconds
        }
        val model = ExpensesViewModel(f.workspace.id, f.observe, f.refresh)
        val owner = ViewModelStore().apply { put("expenses", model) }
        try {
            test(f, model, owner)
        } finally {
            owner.clear()
            runCurrent()
            assertEquals(0, f.observers)
            Dispatchers.resetMain()
        }
    }
}
