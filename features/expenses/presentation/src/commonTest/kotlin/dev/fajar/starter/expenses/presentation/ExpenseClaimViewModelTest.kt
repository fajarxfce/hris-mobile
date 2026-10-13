@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.expenses.presentation

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.common.result.*
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.policies.expenseFailure
import dev.fajar.starter.expenses.presentation.detail.*
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class ExpenseClaimViewModelTest {
    @Test
    fun detailHasOneAcquisitionOwnerAndStopsPendingPaymentReadsOnDisposal() = runTest {
        withModel { f, model, owner ->
            model.onEvent(ExpenseClaimEvent.Entered)
            model.onEvent(ExpenseClaimEvent.Entered)
            runCurrent()
            assertEquals(2, f.claimReads)
            assertEquals(1, f.paymentReads)
            assertNotNull(model.state.value.content)
            var cancelled = false
            f.paymentCall = {
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
            model.onEvent(ExpenseClaimEvent.RefreshRequested)
            model.onEvent(ExpenseClaimEvent.RefreshRequested)
            runCurrent()
            assertNotNull(model.state.value.content)
            owner.clear()
            runCurrent()
            assertTrue(cancelled)
            assertEquals(0, f.observers)
        }
    }

    @Test
    fun historyOnlyAcceptsIssuedCursorsAndCannotReplaceCurrentClaimFacts() = runTest {
        withModel { f, model, _ ->
            f.claimCall = { AppResult.Success(f.claim.copy(version = 2)) }
            val response = CompletableDeferred<Unit>()
            f.historyCall = { after ->
                if (after != null) response.await()
                AppResult.Success(
                    if (after == null) ExpensePage(listOf(f.change.copy(version = 2)), "2")
                    else ExpensePage(listOf(f.change.copy(version = 1)), null)
                )
            }
            model.onEvent(ExpenseClaimEvent.Entered)
            runCurrent()
            model.onEvent(ExpenseClaimEvent.HistoryRequested(999))
            runCurrent()
            assertEquals(1, f.historyReads)
            model.onEvent(ExpenseClaimEvent.HistoryRequested(2))
            runCurrent()
            assertEquals(2L, model.state.value.content?.value?.claim?.version)
            assertEquals(2L, model.state.value.content?.value?.history?.items?.single()?.version)
            assertTrue(model.state.value.refreshing)
            response.complete(Unit)
            runCurrent()
            assertEquals(1L, model.state.value.content?.value?.history?.items?.single()?.version)
            assertEquals(2L, model.state.value.historyAfter)
            model.onEvent(ExpenseClaimEvent.HistoryRequested(null))
            runCurrent()
            assertEquals(2L, model.state.value.content?.value?.history?.items?.single()?.version)
        }
    }

    @Test
    fun failedCleanupAndOfflineRetryKeepTheAuthoritativeMfaGateClosed() = runTest {
        withModel { f, model, _ ->
            model.onEvent(ExpenseClaimEvent.Entered)
            runCurrent()
            f.failCleanup = true
            f.claimCall = { expenseFailure("mfa_required", FailureKind.AccessDenied) }
            model.onEvent(ExpenseClaimEvent.RefreshRequested)
            runCurrent()
            assertNotNull(f.snapshot(true))
            assertTrue(model.state.value.needsVerification)
            assertNull(model.state.value.content)
            val response = CompletableDeferred<Unit>()
            f.claimCall = {
                response.await()
                expenseFailure("fixture.offline", FailureKind.Network)
            }
            model.onEvent(ExpenseClaimEvent.RefreshRequested)
            runCurrent()
            assertTrue(model.state.value.readRestricted)
            response.complete(Unit)
            runCurrent()
            assertEquals("mfa_required", model.state.value.failure?.code)
            assertEquals(0, f.observers)
            f.claimCall = { AppResult.Success(f.claim) }
            model.onEvent(ExpenseClaimEvent.RefreshRequested)
            runCurrent()
            assertNotNull(model.state.value.content)
            assertNull(model.state.value.failure)
            assertEquals(1, f.observers)
        }
    }

    @Test
    fun explicitSuccessfulRefreshReopensACompletedDetailSource() = runTest {
        withModel { f, model, _ ->
            f.failNextDetailObservation = true
            val response = CompletableDeferred<Unit>()
            f.claimCall = {
                response.await()
                AppResult.Success(f.claim)
            }
            model.onEvent(ExpenseClaimEvent.Entered)
            runCurrent()
            assertEquals(FailureKind.Storage, model.state.value.failure?.kind)
            assertEquals(0, f.observers)
            response.complete(Unit)
            runCurrent()
            assertNotNull(model.state.value.content)
            assertNull(model.state.value.failure)
            assertEquals(1, f.observers)
        }
    }

    @Test
    fun stoppingRejectsLateFailuresAndRetainsNoFinancialContent() = runTest {
        withModel { f, model, _ ->
            model.onEvent(ExpenseClaimEvent.Entered)
            runCurrent()
            f.claimCall = {
                try {
                    awaitCancellation()
                } catch (_: CancellationException) {}
                expenseFailure("mfa_required", FailureKind.AccessDenied)
            }
            model.onEvent(ExpenseClaimEvent.RefreshRequested)
            runCurrent()
            model.onEvent(ExpenseClaimEvent.Stopped)
            runCurrent()
            assertNull(model.state.value.content)
            assertNull(model.state.value.failure)
            assertFalse(model.state.value.refreshing)
            assertEquals(0, f.observers)
        }
    }

    private suspend fun TestScope.withModel(
        test: suspend (ExpenseFixture, ExpenseClaimViewModel, ViewModelStore) -> Unit
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = ExpenseFixture {
            Instant.parse("2026-10-13T00:00:00Z") + testScheduler.currentTime.milliseconds
        }
        val model =
            ExpenseClaimViewModel(
                ExpenseClaimTarget(f.workspace.id, f.claim.id),
                f.observeDetail,
                f.refreshDetail,
            )
        val owner = ViewModelStore().apply { put("expense", model) }
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
